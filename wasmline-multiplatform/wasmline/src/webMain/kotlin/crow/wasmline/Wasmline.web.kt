@file:OptIn(ExperimentalWasmJsInterop::class)
@file:Suppress("unused")

package crow.wasmline

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.js

internal class BrowserWasmline(private val moduleKey: String) {
    fun setOutbound(dispatcher: (String, String) -> String) {
        WasmlineWebModuleRegistry.require(moduleKey).setOutbound(dispatcher)
    }

    fun call(action: String, payloadBase64: String): String {
        return WasmlineWebModuleRegistry.require(moduleKey).call(action, payloadBase64)
    }

    fun close() {
        WasmlineWebModuleRegistry.remove(moduleKey)
    }
}

internal object BrowserWasmlineRuntime {
    fun load(
        filepath: String,
        config: WasmlineConfig,
        createWasmline: (String, WasmlineConfig) -> Wasmline,
    ): WasmlineLoadState {
        if (config.threadSafe) {
            return WasmlineLoadState.Failure(
                code = WasmlineLoadState.CODE_FAILURE,
                cause = "[Wasmline] Browser web host does not support threadSafe loading yet.",
            )
        }

        return WasmlineLocalArtifactBridge.load(
            artifactPath = filepath,
            config = config,
            platform = object : WasmlinePlatformArtifactBridge {
                override fun createWasmline(moduleKey: String, config: WasmlineConfig): Wasmline {
                    return createWasmline(moduleKey, config)
                }

                override fun resolveArtifact(path: String): ResolvedPrecompiledArtifact? {
                    return ResolvedPrecompiledArtifact(
                        artifactPath = path,
                        moduleKey = path,
                    )
                }

                override fun backendCodeOrNull(path: String): Byte? {
                    return if (path.substringAfterLast('.', "").lowercase() == "wasm") {
                        WasmlineLoadState.CODE_SUCCESS_WASM
                    } else {
                        null
                    }
                }

                override fun unsupportedArtifactMessage(path: String): String {
                    return "[Wasmline] Browser web host only supports raw .wasm artifacts: $path"
                }

                override fun loadPrecompiled(moduleKey: String, path: String): Boolean {
                    return WasmlineWebModuleRegistry.load(moduleKey, path)
                }

                override fun loadFailureMessage(path: String): String {
                    return WasmlineWebModuleRegistry.failureMessage(path)
                }
            },
        )
    }

    fun bootstrap() = Unit

    fun shutdown() {
        WasmlineWebModuleRegistry.clear()
    }
}

private object WasmlineWebModuleRegistry {
    private val modules = linkedMapOf<String, WasmlineWebModule>()
    private val failures = linkedMapOf<String, String>()

    fun load(moduleKey: String, path: String): Boolean {
        val existing = modules[moduleKey]
        if (existing != null) return true

        return runCatching {
            WasmlineWebModule(newRawWasmlineBrowserModule().also { it.load(path) })
        }.fold(
            onSuccess = { module ->
                failures.remove(path)
                modules[moduleKey] = module
                true
            },
            onFailure = { throwable ->
                failures[path] = throwable.message ?: throwable.toString()
                false
            },
        )
    }

    fun require(moduleKey: String): WasmlineWebModule {
        return checkNotNull(modules[moduleKey]) {
            "Wasmline browser module '$moduleKey' is not loaded."
        }
    }

    fun remove(moduleKey: String) {
        modules.remove(moduleKey)?.close()
    }

    fun clear() {
        modules.values.forEach { it.close() }
        modules.clear()
        failures.clear()
    }

    fun failureMessage(path: String): String {
        val detail = failures[path]
        return if (detail == null) {
            "[Wasmline] Browser web host failed to load artifact: $path"
        } else {
            "[Wasmline] Browser web host failed to load artifact: $path. $detail"
        }
    }
}

private class WasmlineWebModule(
    private val raw: RawWasmlineBrowserModule,
) {
    private var dispatcher: ((String, String) -> String)? = null

    init {
        raw.setDispatcher { action, payloadBase64 ->
            val currentDispatcher = checkNotNull(dispatcher) {
                "No Wasmline outbound dispatcher is bound for action '$action'."
            }
            currentDispatcher(action, payloadBase64)
        }
    }

    fun setOutbound(dispatcher: (String, String) -> String) {
        this.dispatcher = dispatcher
    }

    fun call(action: String, payloadBase64: String): String {
        return raw.call(action, payloadBase64)
    }

    fun close() {
        raw.clearDispatcher()
        raw.close()
    }
}

private external interface RawWasmlineBrowserModule : JsAny {
    fun load(artifactPath: String)
    fun setDispatcher(dispatcher: (String, String) -> String)
    fun clearDispatcher()
    fun call(action: String, payloadBase64: String): String
    fun close()
}

private fun newRawWasmlineBrowserModule(): RawWasmlineBrowserModule = js(
    """
    (() => {
      const encoder = new TextEncoder();
      const decoder = new TextDecoder();
      let instance = null;
      let memory = null;
      let dispatcher = null;
      let inboundAction = new Uint8Array(0);
      let inboundPayload = new Uint8Array(0);
      let inboundResponse = new Uint8Array(0);
      let pendingOutboundResponse = new Uint8Array(0);

      const requireMemoryBuffer = () => {
        if (!memory) throw new Error("Plugin memory is not initialized yet");
        return memory.buffer;
      };

      const readBytes = (pointer, length) => new Uint8Array(requireMemoryBuffer(), pointer, length).slice();

      const writeBytes = (pointer, bytes) => {
        new Uint8Array(requireMemoryBuffer(), pointer, bytes.length).set(bytes);
      };

      const readText = (pointer, length) => decoder.decode(readBytes(pointer, length));

      const bytesToBase64 = (bytes) => {
        if (!bytes || bytes.length === 0) return "";
        let binary = "";
        const chunkSize = 0x8000;
        for (let index = 0; index < bytes.length; index += chunkSize) {
          const chunk = bytes.subarray(index, index + chunkSize);
          for (let inner = 0; inner < chunk.length; inner++) {
            binary += String.fromCharCode(chunk[inner]);
          }
        }
        return btoa(binary);
      };

      const base64ToBytes = (value) => {
        if (!value) return new Uint8Array(0);
        const binary = atob(value);
        const bytes = new Uint8Array(binary.length);
        for (let index = 0; index < binary.length; index++) {
          bytes[index] = binary.charCodeAt(index);
        }
        return bytes;
      };

      const binaryStringToBytes = (value) => {
        if (!value) return new Uint8Array(0);
        const bytes = new Uint8Array(value.length);
        for (let index = 0; index < value.length; index++) {
          bytes[index] = value.charCodeAt(index) & 0xff;
        }
        return bytes;
      };

      const imports = {
        wasi_snapshot_preview1: {
          fd_write(fd, iovs, iovsLen, writtenPtr) {
            const view = new DataView(requireMemoryBuffer());
            let totalLength = 0;
            const parts = [];
            for (let index = 0; index < iovsLen; index++) {
              const offset = iovs + index * 8;
              const pointer = view.getUint32(offset, true);
              const length = view.getUint32(offset + 4, true);
              const part = readBytes(pointer, length);
              parts.push(part);
              totalLength += length;
            }
            const merged = new Uint8Array(totalLength);
            let cursor = 0;
            for (const part of parts) {
              merged.set(part, cursor);
              cursor += part.length;
            }
            if (writtenPtr !== 0) {
              view.setUint32(writtenPtr, totalLength, true);
            }
            const text = decoder.decode(merged).trimEnd();
            if (text.length > 0) {
              if (fd === 2) {
                console.error(text);
              } else {
                console.log(text);
              }
            }
            return 0;
          },
          random_get(pointer, length) {
            globalThis.crypto.getRandomValues(new Uint8Array(requireMemoryBuffer(), pointer, length));
            return 0;
          },
          clock_time_get(clockId, precision, resultPointer) {
            new DataView(requireMemoryBuffer()).setBigUint64(
              resultPointer,
              BigInt(Date.now()) * BigInt(1000000),
              true
            );
            return 0;
          },
        },
        env: {
          bridge_inbound_copy_params(type, pointer, length) {
            const source = type === 0 ? inboundAction : inboundPayload;
            writeBytes(pointer, source.subarray(0, length));
          },
          bridge_inbound_set_response(pointer, length) {
            inboundResponse = length === 0 ? new Uint8Array(0) : readBytes(pointer, length);
          },
          bridge_outbound_call_host(actionPointer, actionLength, payloadPointer, payloadLength, outPointer, outLength) {
            if (!dispatcher) {
              throw new Error("No Wasmline outbound dispatcher is bound.");
            }

            const action = readText(actionPointer, actionLength);
            const payload = readBytes(payloadPointer, payloadLength);
            const responseBase64 = dispatcher(action, bytesToBase64(payload)) || "";
            const responseBytes = base64ToBytes(responseBase64);

            if (responseBytes.length <= outLength) {
              writeBytes(outPointer, responseBytes);
              return responseBytes.length;
            }

            pendingOutboundResponse = responseBytes;
            return -responseBytes.length;
          },
          bridge_outbound_get_response(pointer) {
            writeBytes(pointer, pendingOutboundResponse);
          },
        },
      };

      return {
        load(artifactPath) {
          if (instance !== null) return;

          const xhr = new XMLHttpRequest();
          xhr.open("GET", artifactPath, false);
          xhr.overrideMimeType("text/plain; charset=x-user-defined");
          xhr.send(null);

          if (!((xhr.status >= 200 && xhr.status < 300) || xhr.status === 0)) {
            throw new Error("Unable to fetch plugin: " + xhr.status + " " + xhr.statusText);
          }

          const moduleBytes = binaryStringToBytes(xhr.responseText);
          if (moduleBytes.length === 0) {
            throw new Error("Empty wasm response for: " + artifactPath);
          }

          const module = new WebAssembly.Module(moduleBytes);
          instance = new WebAssembly.Instance(module, imports);
          memory = instance.exports.memory;
          instance.exports.__wasmline_wasi_init();
        },
        setDispatcher(nextDispatcher) {
          dispatcher = nextDispatcher;
        },
        clearDispatcher() {
          dispatcher = null;
        },
        call(action, payloadBase64) {
          if (instance === null) {
            throw new Error("Wasmline browser module is not loaded yet.");
          }

          inboundAction = encoder.encode(action);
          inboundPayload = base64ToBytes(payloadBase64);
          inboundResponse = new Uint8Array(0);
          pendingOutboundResponse = new Uint8Array(0);

          instance.exports.__wasmline_wasi_entry(inboundAction.length, inboundPayload.length);
          return bytesToBase64(inboundResponse);
        },
        close() {
          dispatcher = null;
          instance = null;
          memory = null;
          inboundAction = new Uint8Array(0);
          inboundPayload = new Uint8Array(0);
          inboundResponse = new Uint8Array(0);
          pendingOutboundResponse = new Uint8Array(0);
        },
      };
    })()
    """
)
