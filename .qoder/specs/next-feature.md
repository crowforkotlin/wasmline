# Remote Package Loading: Network Factory Pattern

## Context

wasmline-loader currently supports local file loading (`LocalArtifactFile`, `LocalPackageFile`). `RemotePackageUrl` exists as a source type but has no implementation -- it returns "not supported yet". The loader needs remote package loading, caching, and manifest signature verification to be production-ready.

The core constraint: **wasmline-loader must not depend on any network library** (OkHttp, Ktor, etc.). Instead, we define a minimal `WasmlineNetworkClient` interface in the loader, and provide official adapter modules (`wasmline-network-okhttp`, `wasmline-network-ktor`) as separate libraries.

---

## Architecture

```
Caller provides:
  WasmlineNetworkClient (from adapter module)
  WasmlineTrustedKeys   (app-provided public keys)
  WasmlineCache          (optional, defaults to file cache)
         |
         v
  WasmlineLoadRequest(
    source = RemotePackageUrl(url),
    networkClient = ...,
    trustedKeys = ...,
    cache = ...,
  )
         |
         v
  DefaultWasmlineLoader.loadSource()
         |
         v
  WasmlineRemotePackageResolution (NEW, hostMain)
    1. Check cache for manifest
    2. networkClient.fetch(manifestUrl)
    3. ProtoBuf.decode -> SignedManifestEnvelope
    4. Verify signature (existing Ed25519/ECDSA crypto)
    5. selectArtifact() (reuse existing logic)
    6. Check cache for artifact
    7. networkClient.fetch(artifactUrl)
    8. Verify SHA256
    9. Write artifact to local cache dir
   10. -> ContinueWith(LocalArtifactFile(cachedPath))
```

---

## Phase 1: New Interfaces in wasmline-loader (commonMain)

### 1.1 `WasmlineNetworkClient.kt` (NEW)
**Path:** `wasmline-loader/src/commonMain/kotlin/crow/wasmline/loader/WasmlineNetworkClient.kt`

```kotlin
fun interface WasmlineNetworkClient {
    fun fetch(url: String): WasmlineHttpResponse
}

data class WasmlineHttpResponse(
    val statusCode: Int,
    val bytes: ByteArray,
)
```

- `fun interface` for SAM-conversion, matching existing `WasmlineLoader` / `WasmlineRemotePackageResolver` pattern
- **Blocking** (not suspend): the entire resolver chain is synchronous. Adapter modules bridge async HTTP engines internally (OkHttp's blocking `execute()`, Ktor via `runBlocking`)

### 1.2 `WasmlineCache.kt` (NEW)
**Path:** `wasmline-loader/src/commonMain/kotlin/crow/wasmline/loader/WasmlineCache.kt`

```kotlin
interface WasmlineCache {
    fun get(key: String): ByteArray?
    fun put(key: String, bytes: ByteArray)
    fun exists(key: String): Boolean
}

object WasmlineNoOpCache : WasmlineCache {
    override fun get(key: String): ByteArray? = null
    override fun put(key: String, bytes: ByteArray) {}
    override fun exists(key: String): Boolean = false
}
```

### 1.3 `WasmlineTrustedKeys.kt` (NEW)
**Path:** `wasmline-loader/src/commonMain/kotlin/crow/wasmline/loader/WasmlineTrustedKeys.kt`

```kotlin
fun interface WasmlineTrustedKeys {
    fun getPublicKey(algorithm: String, keyId: String?): ByteArray?
}

class WasmlineTrustedKeySet private constructor(
    private val entries: List<TrustedKeyEntry>,
) : WasmlineTrustedKeys {
    private data class TrustedKeyEntry(
        val algorithm: String,
        val keyId: String?,
        val publicKey: ByteArray,
    )
    override fun getPublicKey(algorithm: String, keyId: String?): ByteArray? { ... }
    class Builder { fun add(algorithm, keyId?, publicKey): Builder; fun build(): WasmlineTrustedKeySet }
}
```

- `null` keyId acts as wildcard (matches any keyId for that algorithm)
- When `trustedKeys` is null on the request, signature verification is skipped (permissive mode, backward-compatible)

### 1.4 Modify `WasmlineLoadRequest`
**Path:** `wasmline-loader/src/commonMain/kotlin/crow/wasmline/loader/WasmlineLoadRequest.kt`

Add 3 new nullable fields:
```kotlin
data class WasmlineLoadRequest(
    val source: WasmlineSource,
    val threadSafe: Boolean = false,
    val config: WasmlineConfig = WasmlineConfig(),
    val metadata: Map<String, String> = emptyMap(),
    val resolvers: WasmlineSourceResolvers = WasmlineSourceResolvers(),
    // NEW:
    val networkClient: WasmlineNetworkClient? = null,
    val trustedKeys: WasmlineTrustedKeys? = null,
    val cache: WasmlineCache? = null,
)
```

All nullable with null defaults -- fully backward-compatible.

---

## Phase 2: Remote Resolution Core (hostMain)

### 2.1 `WasmlineRemotePackageResolution.kt` (NEW)
**Path:** `wasmline-loader/src/hostMain/kotlin/crow/wasmline/loader/internal/WasmlineRemotePackageResolution.kt`

Placed in **hostMain** (not commonMain) because the final step writes the artifact to the local filesystem, consistent with existing `WasmlineLocalPackageResolution`.

**Resolution flow:**

| Step | Action | On Failure |
|------|--------|------------|
| 1 | Validate `request.networkClient != null` | "No network client configured" |
| 2 | Determine manifest URL (append `/manifest.wlm` if not `.wlm`) | - |
| 3 | Check cache for manifest (`manifest_{sha256(url)}`) | - |
| 4 | Fetch manifest via `networkClient.fetch()` | HTTP error / network exception |
| 5 | Cache manifest bytes | - |
| 6 | `ProtoBuf.decodeFromByteArray(SignedManifestEnvelope)` | "Failed to parse manifest" |
| 7 | If `trustedKeys != null`: verify signature using existing crypto | "Signature verification failed" |
| 8 | `selectArtifact(envelope.manifest.artifacts)` (reuse existing) | "No compatible artifact" |
| 9 | Resolve artifact URL (relative to manifest base) | - |
| 10 | Check cache for artifact (`artifact_{sha256}`) | - |
| 11 | Fetch artifact via `networkClient.fetch()` | HTTP error |
| 12 | Verify SHA256 of artifact bytes | "SHA256 mismatch" |
| 13 | Cache artifact bytes | - |
| 14 | Write artifact to local file via `writeCachedArtifact()` | I/O error |
| 15 | Return `ContinueWith(LocalArtifactFile(localPath))` | - |

**Signature verification glue** (uses existing crypto):
```kotlin
val algorithmId = SignatureAlgorithmId.valueOf(envelope.algorithm)
val algorithm = algorithmId.get()  // InternalCommon.kt
val manifestBytes = ProtoBuf.encodeToByteArray(WasmlineManifest.serializer(), envelope.manifest)
val publicKey = trustedKeys.getPublicKey(envelope.algorithm, envelope.publicKeyId)
    ?: return failure("No trusted key for algorithm=${envelope.algorithm}, keyId=${envelope.publicKeyId}")
if (!algorithm.verify(manifestBytes.toByteString(), envelope.signature.toByteString(), publicKey.toByteString())) {
    return failure("Manifest signature verification failed")
}
```

### 2.2 Modify `DefaultWasmlineLoader.loadSource()`
**Path:** `wasmline-loader/src/hostMain/kotlin/crow/wasmline/loader/WasmlineLoader.kt`

In the `RemotePackageUrl` branch, add auto-delegation logic:

```kotlin
is WasmlineSource.RemotePackageUrl -> {
    // Priority 1: caller's custom resolver
    val customResolution = request.resolvers.remotePackage?.resolve(source, request)
    if (customResolution != null) {
        resolveSource(request, customResolution, ...)
    }
    // Priority 2: built-in remote resolution (when networkClient provided)
    else if (request.networkClient != null) {
        val builtInResolution = WasmlineRemotePackageResolution.resolve(source, request)
        resolveSource(request, builtInResolution, ...)
    }
    // Fallback: existing error
    else {
        unsupportedSourceFailure(...)
    }
}
```

### 2.3 New Host File I/O Functions

**Modify:** `wasmline-loader/src/hostMain/kotlin/crow/wasmline/loader/internal/HostFileAccess.kt`
```kotlin
internal expect fun writeHostFileBytes(path: String, bytes: ByteArray): Boolean
internal expect fun hostMkdirs(path: String): Boolean
```

**Add actuals:**
- `jniMain/HostFileAccess.jni.kt`: `File(path).writeBytes(bytes)` / `File(path).mkdirs()`
- `iosMain/HostFileAccess.ios.kt`: `NSFileManager` write/createDirectory
- `webMain/HostFileAccess.web.kt`: helpers return `false`
- `jsMain/HostFileAccess.js.kt`: delegate to web helpers
- `wasmJsMain/HostFileAccess.wasmJs.kt`: delegate to web helpers

### 2.4 Default File Cache

**New:** `wasmline-loader/src/hostMain/kotlin/crow/wasmline/loader/internal/WasmlineFileCache.kt`

```kotlin
internal class WasmlineFileCache(private val cacheDirectory: String) : WasmlineCache {
    override fun get(key: String): ByteArray? = readHostFileBytes("$cacheDirectory/$key")
    override fun put(key: String, bytes: ByteArray) {
        hostMkdirs(cacheDirectory)
        writeHostFileBytes("$cacheDirectory/$key", bytes)
    }
    override fun exists(key: String): Boolean = hostPathExists("$cacheDirectory/$key")
}
```

**Default cache directory** (new expect/actual):
- `jniMain`: `System.getProperty("user.home") + "/.wasmline/cache"`
- `iosMain`: `NSCachesDirectory + "/wasmline"`
- `webMain`: not applicable (use `WasmlineNoOpCache`)

---

## Phase 3: Network Adapter Modules

### 3.1 `wasmline-network-okhttp`

**Structure:**
```
wasmline-multiplatform/wasmline-network-okhttp/
  build.gradle.kts
  src/commonMain/kotlin/crow/wasmline/network/okhttp/OkHttpNetworkClient.kt
```

**build.gradle.kts:**
- Plugins: `kotlin.multiplatform`, `android.library.kmp`, `maven.publish`
- Targets: `jvm()`, `androidLibrary` (OkHttp is JVM-only)
- Dependencies: `api(projects.wasmlineLoader)`, `implementation(libs.okhttp)`

**Implementation:**
```kotlin
class OkHttpNetworkClient(
    private val client: OkHttpClient = OkHttpClient(),
) : WasmlineNetworkClient {
    override fun fetch(url: String): WasmlineHttpResponse {
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()  // blocking
        return WasmlineHttpResponse(
            statusCode = response.code,
            bytes = response.body.bytes(),
        )
    }
}
```

### 3.2 `wasmline-network-ktor`

**Structure:**
```
wasmline-multiplatform/wasmline-network-ktor/
  build.gradle.kts
  src/commonMain/kotlin/crow/wasmline/network/ktor/KtorNetworkClient.kt
```

**build.gradle.kts:**
- Plugins: `kotlin.multiplatform`, `android.library.kmp`, `maven.publish`
- Targets: `jvm()`, `androidLibrary`, `wasmJs`, `js`, `iosArm64()`, `iosSimulatorArm64()`
- Source set hierarchy: mirror loader (commonMain -> hostMain -> jniMain/iosMain/webMain)
- Dependencies:
  - commonMain: `api(projects.wasmlineLoader)`, `implementation(libs.ktor.client.core)`
  - jvmMain: `implementation(libs.ktor.client.cio)`
  - androidMain: `implementation(libs.ktor.client.okhttp)`
  - iosMain: `implementation(libs.ktor.client.darwin)`
  - webMain: `implementation(libs.ktor.client.js)`

**Implementation:**
```kotlin
class KtorNetworkClient(
    private val client: HttpClient = HttpClient(),
) : WasmlineNetworkClient {
    override fun fetch(url: String): WasmlineHttpResponse {
        return runBlocking {
            val response = client.get(url)
            WasmlineHttpResponse(
                statusCode = response.status.value,
                bytes = response.body<ByteArray>(),
            )
        }
    }
}
```

**Web platform note:** `runBlocking` is not available in JS. On web platforms, `KtorNetworkClient.fetch()` throws `UnsupportedOperationException`. Browser callers should provide a custom `WasmlineRemotePackageResolver` with async logic instead.

### 3.3 Module Registration

**Modify:** `wasmline-multiplatform/settings.gradle.kts`
```kotlin
include(":wasmline-network-okhttp")
include(":wasmline-network-ktor")
```

---

## Phase 4: Signature Verification Integration

Already covered in Phase 2 step 7. Key points:

- When `trustedKeys` is null: skip verification (permissive, backward-compatible)
- When `trustedKeys` is provided: verify signature, fail if key not found or mismatch
- Uses existing `SignatureAlgorithmId.valueOf()` -> `.get()` -> `.verify()` pipeline
- The `ManifestTest.kt` in commonTest already demonstrates the exact verification flow

---

## File Change Summary

### New Files (12)

| File | Description |
|------|-------------|
| `wasmline-loader/src/commonMain/.../WasmlineNetworkClient.kt` | `fun interface` + `WasmlineHttpResponse` |
| `wasmline-loader/src/commonMain/.../WasmlineCache.kt` | `interface WasmlineCache` + `WasmlineNoOpCache` |
| `wasmline-loader/src/commonMain/.../WasmlineTrustedKeys.kt` | `fun interface` + `WasmlineTrustedKeySet` builder |
| `wasmline-loader/src/hostMain/.../internal/WasmlineRemotePackageResolution.kt` | Core remote resolution logic |
| `wasmline-loader/src/hostMain/.../internal/WasmlineFileCache.kt` | File-system cache implementation |
| `wasmline-loader/src/hostMain/.../internal/DefaultCacheDirectory.kt` | `expect fun defaultCacheDirectory(): String` |
| `wasmline-loader/src/jniMain/.../internal/DefaultCacheDirectory.jni.kt` | JVM actual cache dir |
| `wasmline-loader/src/iosMain/.../internal/DefaultCacheDirectory.ios.kt` | iOS actual cache dir |
| `wasmline-network-okhttp/build.gradle.kts` | OkHttp adapter module build |
| `wasmline-network-okhttp/src/commonMain/.../OkHttpNetworkClient.kt` | OkHttp `WasmlineNetworkClient` impl |
| `wasmline-network-ktor/build.gradle.kts` | Ktor adapter module build |
| `wasmline-network-ktor/src/commonMain/.../KtorNetworkClient.kt` | Ktor `WasmlineNetworkClient` impl |

### Modified Files (11)

| File | Change |
|------|--------|
| `wasmline-loader/src/commonMain/.../WasmlineLoadRequest.kt` | Add `networkClient`, `trustedKeys`, `cache` fields |
| `wasmline-loader/src/hostMain/.../WasmlineLoader.kt` | RemotePackageUrl branch: auto-delegate to built-in resolver |
| `wasmline-loader/src/hostMain/.../internal/HostFileAccess.kt` | Add `writeHostFileBytes`, `hostMkdirs` expect |
| `wasmline-loader/src/jniMain/.../internal/HostFileAccess.jni.kt` | Add actuals for new file I/O |
| `wasmline-loader/src/iosMain/.../internal/HostFileAccess.ios.kt` | Add actuals for new file I/O |
| `wasmline-loader/src/webMain/.../internal/HostFileAccess.web.kt` | Add browser helpers for new functions |
| `wasmline-loader/src/jsMain/.../internal/HostFileAccess.js.kt` | Delegate to web helpers |
| `wasmline-loader/src/wasmJsMain/.../internal/HostFileAccess.wasmJs.kt` | Delegate to web helpers |
| `wasmline-multiplatform/settings.gradle.kts` | Include new modules |

### Test Files (3)

| File | Description |
|------|-------------|
| `wasmline-loader/src/commonTest/.../WasmlineRemotePackageResolutionTest.kt` | Unit tests with `FakeNetworkClient` |
| `wasmline-loader/src/commonTest/.../WasmlineTrustedKeysTest.kt` | Trusted key lookup tests |
| `wasmline-loader/src/jvmTest/.../DefaultWasmlineLoaderRemoteTest.kt` | Integration: auto-delegation test |

---

## Verification

1. **Unit tests**: Run `WasmlineRemotePackageResolutionTest` with a `FakeNetworkClient` that serves in-memory signed manifests and artifacts. Verify all 10 resolution steps including cache hits, signature pass/fail, SHA256 verification.

2. **Build verification** (requires JBR 21):
   ```bash
   cd wasmline-multiplatform
   ./gradlew :wasmline-loader:compileKotlinJvm
   ./gradlew :wasmline-network-okhttp:compileKotlinJvm
   ./gradlew :wasmline-network-ktor:compileKotlinJvm
   ./gradlew :wasmline-loader:jvmTest
   ```

3. **Existing tests**: Ensure `DefaultWasmlineLoaderTest` and `ManifestTest` still pass -- no breaking changes to existing APIs.

---

## Implementation Order

1. New interfaces (`WasmlineNetworkClient`, `WasmlineCache`, `WasmlineTrustedKeys`) + modify `WasmlineLoadRequest`
2. New expect/actual file I/O functions (`writeHostFileBytes`, `hostMkdirs`)
3. `WasmlineFileCache` + `DefaultCacheDirectory`
4. `WasmlineRemotePackageResolution` core logic + signature verification wiring
5. Modify `DefaultWasmlineLoader.loadSource()` for auto-delegation
6. `wasmline-network-okhttp` module
7. `wasmline-network-ktor` module
8. Tests
9. `settings.gradle.kts` module registration
