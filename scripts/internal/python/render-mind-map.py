#!/usr/bin/env python3
"""Render the bilingual summaries of design-mind.md to the documentation PNGs."""

from __future__ import annotations

import argparse
import re
from dataclasses import dataclass
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont, PngImagePlugin


REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
RENDERER_PATH = Path(__file__).resolve().relative_to(REPOSITORY_ROOT).as_posix()
BACKGROUND = "#0b1220"
COLORS = (
    "#52d6c1",
    "#ac96f5",
    "#6ebaf4",
    "#f0bd65",
    "#f18fab",
    "#7ed0ee",
    "#b3c881",
    "#d4a0ec",
    "#eea777",
)


@dataclass
class Node:
    en: str
    zh: str
    children: tuple[Node, ...] = ()


def n(en: str, zh: str, *children: Node) -> Node:
    return Node(en, zh, children)


# Keep these summaries consistent with the contracts and named sources in design-mind.md.
MIND_MAP = n(
    "Wasmline\nTechnical Mind Map",
    "Wasmline\n技术思维导图",
    n(
        "1. Runtime axes and artifacts",
        "1. 运行属性与产物",
        n(
            "WasmlineArtifactDescriptor",
            "WasmlineArtifactDescriptor",
            n("Format: RAW_WASM / CWASM / PWASM", "物理格式：RAW_WASM / CWASM / PWASM"),
            n("Model: CORE_WASM / COMPONENT_MODEL", "执行模型：CORE_WASM / COMPONENT_MODEL"),
            n(
                "Protocol: WASMLINE_SERVICE /\nRAW_EXPORT / COMPONENT_EXPORT",
                "调用协议：WASMLINE_SERVICE /\nRAW_EXPORT / COMPONENT_EXPORT",
            ),
        ),
        n(
            "Supported combinations",
            "支持的属性组合",
            n(
                "CORE_WASM: WASMLINE_SERVICE or RAW_EXPORT",
                "CORE_WASM：WASMLINE_SERVICE 或 RAW_EXPORT",
            ),
            n(
                "COMPONENT_MODEL: WASMLINE_SERVICE or COMPONENT_EXPORT",
                "COMPONENT_MODEL：WASMLINE_SERVICE 或 COMPONENT_EXPORT",
            ),
        ),
        n(
            "Physical output",
            "文件内容与执行平台",
            n("Raw .wasm: build input; browser Core runtime", "原始 .wasm：构建输入；浏览器执行 Core Wasm"),
            n(".cwasm: Cranelift AOT for a specific platform", ".cwasm：特定平台的 Cranelift AOT 产物"),
            n(".pwasm: Wasmtime Pulley bytecode", ".pwasm：Wasmtime 生成的 Pulley 字节码"),
            n("iOS: pulley64 .pwasm only", "iOS：仅加载 pulley64 .pwasm"),
        ),
    ),
    n(
        "2. Repository modules",
        "2. 仓库模块",
        n(
            "wasmline-core",
            "wasmline-core",
            n("Api.cpp → NativeRuntime → Wasmtime", "Api.cpp → NativeRuntime → Wasmtime"),
            n(
                "Module / Component caches; Service / Raw / Component sessions",
                "Module / Component 缓存；Service / Raw / Component 会话",
            ),
        ),
        n(
            "wasmline",
            "wasmline",
            n("commonMain: contracts, descriptors, results, serialization", "commonMain：合同、描述符、结果与序列化"),
            n("hostMain: Service, Raw Export, Component APIs", "hostMain：Service、Raw Export 与 Component API"),
            n("jniMain / iosMain: native bridges", "jniMain / iosMain：原生调用实现"),
            n("webMain + jsMain / wasmJsMain: browser Core runtime", "webMain + jsMain / wasmJsMain：浏览器 Core 运行时"),
            n("wasmWasiMain: guest router and Component Service init", "wasmWasiMain：插件路由与 Component Service 初始化"),
        ),
        n(
            "Loader, engines, and BOM",
            "加载器、引擎与 BOM",
            n("wasmline-loader + network-ktor / network-okhttp", "wasmline-loader + network-ktor / network-okhttp"),
            n("engine-cranelift / engine-pulley: native distributions", "engine-cranelift / engine-pulley：原生引擎分发"),
            n("wasmline-android source project: native build inputs", "wasmline-android 源码项目：原生构建输入"),
            n("wasmline-android Maven coordinate: :wasmline Android target", "wasmline-android Maven 坐标：:wasmline 的 Android 目标"),
            n("wasmline-bom: strict same-version constraints", "wasmline-bom：严格约束其声明模块使用同一版本"),
        ),
        n(
            "Build tools",
            "构建工具",
            n("kotlin-plugin: IR contract validation and bridge generation", "kotlin-plugin：IR 合同检查与调用类生成"),
            n("plugin-core: tools, AOT, signatures, packages, WIT bindings", "plugin-core：工具、AOT、签名、打包与 WIT 绑定"),
            n("gradle-plugin / CLI: DSL and command adapters", "gradle-plugin / CLI：Gradle DSL 与命令入口"),
        ),
    ),
    n(
        "3. Signed packages and selection",
        "3. 签名包与产物选择",
        n(
            "manifest.wlm",
            "manifest.wlm",
            n("Trusted keys verify Ed25519 / ECDSA-P256 signatures", "使用可信公钥验证 Ed25519 / ECDSA-P256 签名"),
            n("One manifest: several AOT generations and backend variants", "一个清单：多个 AOT generation 与后端产物"),
            n("artifacts/sha256/{prefix}/{digest}.{extension}", "artifacts/sha256/{prefix}/{digest}.{extension}"),
        ),
        n(
            "WasmlineArtifactSelector.select()",
            "WasmlineArtifactSelector.select()",
            n("Match format, CPU, OS, pointer width, and backend profile ID", "匹配格式、CPU、OS、指针宽度与后端 profile ID"),
            n("Cranelift: matching CWASM first, compatible PWASM fallback", "Cranelift：优先匹配 CWASM，再选择兼容 PWASM"),
            n("Pulley: PWASM only; browser: raw Core Wasm", "Pulley：仅 PWASM；浏览器：原始 Core Wasm"),
            n("Reject equally compatible variants; verify selected size / SHA-256", "拒绝多个同等匹配产物；加载前核对大小与 SHA-256"),
        ),
        n(
            "Source and cache behavior",
            "输入与缓存行为",
            n("Remote package: download manifest and one selected artifact", "远程包：下载清单与选中的一个产物"),
            n("load(descriptor): caller-trusted artifact; no manifest signature", "load(descriptor)：调用方信任产物，不验证清单签名"),
            n("manifestTtlMs: freshness; maxCacheBytes: best-effort cache limit", "manifestTtlMs：缓存新鲜期；maxCacheBytes：尽量限制缓存大小"),
        ),
    ),
    n(
        "4. Native and browser execution",
        "4. 原生与浏览器执行",
        n(
            "Native loading",
            "原生加载",
            n("WasmlineLoader → WasmlineRuntimeBridge → JNI / C interop", "WasmlineLoader → WasmlineRuntimeBridge → JNI / C interop"),
            n("Api.cpp → NativeRuntime → Module / Component → session", "Api.cpp → NativeRuntime → Module / Component → 会话"),
            n("Deserialize AOT only; compilation belongs to build tools", "仅反序列化 AOT；编译由构建工具执行"),
            n("CWASM selects Cranelift; PWASM selects Pulley", "CWASM 选择 Cranelift；PWASM 选择 Pulley"),
        ),
        n(
            "Lifecycle",
            "对象创建与释放",
            n("Session registries retain cloned engine and artifact handles", "会话持有克隆的引擎与产物句柄，由对应 registry 查找"),
            n("Cached calls avoid the global lifecycle mutex", "缓存会话调用不获取全局生命周期互斥锁"),
            n("Release: detach sessions, release cache, destroy outside global lock", "释放：移出会话、释放缓存，在全局锁外销毁会话"),
            n("Engine switch requires no loaded artifacts", "切换引擎要求当前没有已加载产物"),
        ),
        n(
            "Browser loading",
            "浏览器加载",
            n("WasmlineWeb.prefetch / registerBytes → WebWasmArtifacts", "WasmlineWeb.prefetch / registerBytes → WebWasmArtifacts"),
            n("WasmlineLoader.load [suspend] → WebAssembly.Module / Instance", "WasmlineLoader.load [suspend] → WebAssembly.Module / Instance"),
            n("Raw imports: register before instantiate; synchronous callbacks", "Raw import：实例化前注册；同步执行回调"),
            n("i64 uses BigInt; memory views refresh after memory.grow", "i64 使用 BigInt；memory.grow 后刷新内存视图"),
            n("Core Service / Raw Export only; no Component or concurrent loading", "支持 Core Service / Raw Export；不支持 Component 或并发加载"),
        ),
    ),
    n(
        "5. Service and Raw Export calls",
        "5. Service 与 Raw Export 调用",
        n(
            "Wasmline Service",
            "Wasmline Service",
            n("module.link<Service>() → generated *_WasmlineBridge", "module.link<Service>() → 生成的 *_WasmlineBridge"),
            n("Action: fully-qualified contract + # + method name", "动作标识：合同全限定名 + # + 方法名"),
            n("Serialization → callResult → Session / WebWasmPlugin → Router", "序列化 → callResult → Session / WebWasmPlugin → Router"),
            n("Bound implementation → WLMF frame → WasmlineCallResult", "绑定的实现 → WLMF 响应帧 → WasmlineCallResult"),
            n("Guest-to-host callbacks use the outbound dispatcher", "插件调用宿主时使用 outbound dispatcher"),
        ),
        n(
            "Raw Core ABI",
            "Raw Core ABI",
            n("loadCoreModule → register imports → instantiate → CoreWasmSession", "loadCoreModule → 注册 imports → instantiate → CoreWasmSession"),
            n("Explicit numeric exports; CoreWasmModule / RawMemory APIs", "显式声明数值导出；使用 CoreWasmModule / RawMemory API"),
        ),
        n(
            "Failures and memory bounds",
            "失败结果与内存范围",
            n("Normal failures are values; throwOnFailure() is explicit", "正常调用失败返回结果值；throwOnFailure() 显式抛出异常"),
            n("Native Core: A → A or A → B → A returns REENTRANT_CALL", "原生 Core：A → A 或 A → B → A 返回 REENTRANT_CALL"),
            n("Validate guest memory ranges and signed i32 payload lengths", "访问前检查插件内存范围与有符号 i32 载荷长度"),
        ),
    ),
    n(
        "6. Component Model",
        "6. Component Model",
        n(
            "Build and WIT identity",
            "构建与 WIT 标识",
            n("WIT + guest → wit-bindgen → Core Wasm", "WIT + 插件源码 → wit-bindgen → Core Wasm"),
            n("ComponentPipeline → component embed / new → raw Component", "ComponentPipeline → component embed / new → 原始 Component"),
            n("Locked Wasmtime compiler → Component CWASM / PWASM", "锁定的 Wasmtime 编译器 → Component CWASM / PWASM"),
            n("WitDigest.sha256 → WitSources: shared source-tree digest", "WitDigest.sha256 → WitSources：统一计算 WIT 源码摘要"),
            n("wit generate --language: Kotlin default; selectable guest generator", "wit generate --language：默认 Kotlin，可选择插件绑定生成语言"),
        ),
        n(
            "Invocation contracts",
            "调用合同",
            n("WASMLINE_SERVICE: wasmline:service@1.0.0 WIT envelope", "WASMLINE_SERVICE：wasmline:service@1.0.0 WIT 请求格式"),
            n("Serialized service bytes travel inside list<u8>", "序列化服务字节存放在 list<u8> 中"),
            n("COMPONENT_EXPORT: typed values, host imports, explicit instances", "COMPONENT_EXPORT：类型化值、宿主 imports 与显式实例"),
            n("Generated bindings match package ID, world, and WIT digest", "生成的绑定核对 package ID、world 与 WIT 摘要"),
        ),
        n(
            "Resources and instance calls",
            "资源与实例调用",
            n("own<T>: instance-owned; borrow<T>: call-scoped", "own<T>：实例拥有；borrow<T>：仅本次调用可用"),
            n("transferToComponent() consumes ownership before invocation", "transferToComponent() 在调用前消耗 wrapper 的所有权"),
            n("Rejected drop stays open; consumed cleanup failure closes wrapper", "拒绝 drop 时保持打开；引用已消耗而清理失败时关闭 wrapper"),
            n("Per-instance mutation lock; Component reentry fails", "实例修改使用同一把锁；Component 会话拒绝重入"),
            n("Synchronous guest calls expose no cancellation token", "插件调用同步执行，公开 API 没有取消令牌"),
        ),
    ),
    n(
        "7. Kotlin compiler IR",
        "7. Kotlin 编译器 IR",
        n(
            "WasmlineIrGenerationExtension",
            "WasmlineIrGenerationExtension",
            n("Registrar + command-line options → discover service interfaces", "Registrar + 编译参数 → 查找服务接口"),
            n("Validate → generate *_WasmlineBridge → rewrite link / bind", "检查合同 → 生成 *_WasmlineBridge → 重写 link / bind"),
            n("Core WASI exports or Component Service initialization hook", "生成 Core WASI 导出，或连接 Component Service 初始化函数"),
        ),
        n(
            "Contract rules",
            "服务合同规则",
            n("Multiple regular parameters are supported", "支持多个普通参数"),
            n("Reject generics, properties, overloads, suspend, non-public methods", "拒绝泛型、属性、重载、suspend 与非公开方法"),
            n("Reject extension receivers, default / vararg parameters, service values", "拒绝扩展接收者、默认 / vararg 参数与服务类型参数或返回值"),
            n("Unrewritten link() / bind() fail at runtime", "未被编译器重写的 link() / bind() 在运行时失败"),
        ),
    ),
    n(
        "8. AOT versions and releases",
        "8. AOT 版本与发行",
        n(
            "Authoritative sources",
            "版本与兼容记录来源",
            n("versions.json: shared Maven version and toolchain versions", "versions.json：共用 Maven 版本与工具链版本"),
            n("aot-compatibility.json: release intervals and AOT generations", "aot-compatibility.json：发行区间与 AOT generation"),
            n("aot sync: packaged catalog / lock + native / Kotlin identities", "aot sync：生成内置 catalog / lock 与原生 / Kotlin 标识"),
            n("Backend profile IDs determine serialized-artifact compatibility", "使用后端 profile ID 判断序列化产物兼容性"),
        ),
        n(
            "generations(...) / --aot-generations",
            "generations(...) / --aot-generations",
            n("Default Current; explicit Latest / Earliest / All", "默认 Current；可显式选择 Latest / Earliest / All"),
            n("before / after: extra adjacent generations plus anchor", "before / after：包含锚点，再增加相邻 generation"),
            n("Concrete release points, lists, or closed min..max ranges", "使用具体发行版本、版本列表或包含两端的 min..max 区间"),
            n("First explicit call replaces default; later calls add a deduplicated union", "首次显式调用替换默认值；后续调用合并并去重"),
            n("Local resolution rejects missing neighbors / invalid bounds; no network", "本地解析；缺少相邻代次或范围无效时失败，不查询网络"),
            n("Check task is advisory; suppression hides only its log message", "兼容检查给出建议；抑制配置只隐藏日志"),
        ),
        n(
            "Maven and publication",
            "Maven 与发布",
            n("io.github.crowforkotlin; plugin io.github.crowforkotlin.wasmline", "io.github.crowforkotlin；插件 io.github.crowforkotlin.wasmline"),
            n("All modules: x.y.z / x.y.z-alphaN / x.y.z-betaN", "全部模块：x.y.z / x.y.z-alphaN / x.y.z-betaN"),
            n("libs.wasmline / libs.versions.wasmline / libs.plugins.wasmline", "libs.wasmline / libs.versions.wasmline / libs.plugins.wasmline"),
            n("release-<Maven-version>.<major×100 + minor×10 + patch>", "release-<Maven-version>.<major×100 + minor×10 + patch>"),
            n("Validated tag → JVM / iOS gates → one macOS Maven publisher", "检查 tag → JVM / iOS 测试 → macOS 一次发布 Maven"),
            n("GitHub Release: notes + catalog + SHA-256; alpha / beta are Pre-release", "GitHub Release：说明 + catalog + SHA-256；alpha / beta 标记预发行"),
            n("Keep published history; beta → stable alone adds no AOT generation", "保留已发布记录；仅从 beta 升为正式版不增加 AOT generation"),
        ),
    ),
    n(
        "9. Test sources and fixtures",
        "9. 测试源码与生成产物",
        n(
            "wasmline-native-test-fixtures",
            "wasmline-native-test-fixtures",
            n("assembleNativeTestFixtures → build/ AOT files + fixture-index.json", "assembleNativeTestFixtures → build/ 中的 AOT 文件与 fixture-index.json"),
            n("nativeAotJvmTest consumes generated index; iOS selects pulley64", "nativeAotJvmTest 读取生成的索引；iOS 选择 pulley64"),
            n("Indexed path, size, and SHA-256 are checked before test loading", "测试加载前核对索引路径、大小与 SHA-256"),
        ),
        n(
            "Source locations",
            "源码位置",
            n("wasmline/src/jvmTest, iosTest, webTest; wasmline-loader/src", "wasmline/src/jvmTest、iosTest、webTest；wasmline-loader/src"),
            n("plugin-core/src/test; wasmline-plugin-test; kotlin-plugin/testData", "plugin-core/src/test；wasmline-plugin-test；kotlin-plugin/testData"),
            n("Generated test-gen / snapshots / build assets are not edited manually", "test-gen、快照与 build 产物由工具生成，不手动修改"),
        ),
    ),
)


@dataclass
class Layout:
    lines: list[str]
    font: ImageFont.FreeTypeFont
    width: int
    label_height: int
    height: int
    depth: int
    color: str
    children: list[Layout]
    x: int = 0
    y: float = 0


def wrap_text(text: str, font: ImageFont.FreeTypeFont, limit: int) -> list[str]:
    lines = []
    for paragraph in text.split("\n"):
        line = ""
        for token in re.findall(r"[A-Za-z0-9_./:#@<>=+*(),\[\]-]+|\s+|.", paragraph):
            if line and font.getlength(line + token) > limit:
                lines.append(line.rstrip())
                line = ""
            # Long symbol or path tokens also need to fit the selected column.
            for character in token:
                if line and font.getlength(line + character) > limit:
                    lines.append(line.rstrip())
                    line = ""
                if line or not character.isspace():
                    line += character
        lines.append(line.rstrip())
    return lines


def measure(
    node: Node,
    language: str,
    fonts: list[ImageFont.FreeTypeFont],
    depth: int = 0,
    color: str = "#82c5f2",
) -> Layout:
    font = fonts[min(depth, len(fonts) - 1)]
    lines = wrap_text(getattr(node, language), font, (310, 490, 760, 1220)[min(depth, 3)])
    width = int(max(font.getlength(line) for line in lines)) + 36
    label_height = len(lines) * (font.size + 12) + 20
    children = [
        measure(child, language, fonts, depth + 1, COLORS[index] if depth == 0 else color)
        for index, child in enumerate(node.children)
    ]
    gap = 54 if depth == 0 else 14
    children_height = sum(child.height for child in children) + gap * max(0, len(children) - 1)
    return Layout(lines, font, width, label_height, max(label_height, children_height), depth, color, children)


def column_widths(node: Layout, widths: list[int]) -> None:
    while len(widths) <= node.depth:
        widths.append(0)
    widths[node.depth] = max(widths[node.depth], node.width)
    for child in node.children:
        column_widths(child, widths)


def place(node: Layout, columns: list[int], top: float) -> None:
    node.x = columns[node.depth]
    if not node.children:
        node.y = top + node.height / 2
        return
    gap = 54 if node.depth == 0 else 14
    children_height = sum(child.height for child in node.children) + gap * (len(node.children) - 1)
    child_top = top + (node.height - children_height) / 2
    for child in node.children:
        place(child, columns, child_top)
        child_top += child.height + gap
    node.y = (node.children[0].y + node.children[-1].y) / 2


def draw_connections(draw: ImageDraw.ImageDraw, node: Layout) -> None:
    for child in node.children:
        start_x, end_x = node.x + node.width, child.x
        bend_x = (start_x + end_x) / 2
        points = []
        for step in range(41):
            t = step / 40
            u = 1 - t
            points.append(
                (
                    u**3 * start_x + 3 * u**2 * t * bend_x + 3 * u * t**2 * bend_x + t**3 * end_x,
                    u**3 * node.y + 3 * u**2 * t * node.y + 3 * u * t**2 * child.y + t**3 * child.y,
                )
            )
        draw.line(points, fill=child.color, width=3)
        draw_connections(draw, child)


def draw_labels(draw: ImageDraw.ImageDraw, node: Layout) -> None:
    top = node.y - node.label_height / 2
    box = (node.x, top, node.x + node.width, top + node.label_height)
    draw.rounded_rectangle(
        box,
        radius=12 if node.depth < 2 else 7,
        fill="#152339" if node.depth < 2 else "#101b2b",
        outline=node.color if node.depth < 2 else "#27364b",
        width=2 if node.depth < 2 else 1,
    )
    for index, line in enumerate(node.lines):
        draw.text(
            (node.x + 18, top + 10 + index * (node.font.size + 12)),
            line,
            font=node.font,
            fill=node.color if node.depth == 1 else "#e5edf7",
            anchor="lt",
        )
    for child in node.children:
        draw_labels(draw, child)


def render(language: str, regular_font: Path, bold_font: Path) -> None:
    fonts = [
        ImageFont.truetype(str(bold_font), 42),
        ImageFont.truetype(str(bold_font), 32),
        ImageFont.truetype(str(bold_font), 28),
        ImageFont.truetype(str(regular_font), 26),
    ]
    root = measure(MIND_MAP, language, fonts)
    widths: list[int] = []
    column_widths(root, widths)
    columns = [54]
    for width in widths[:-1]:
        columns.append(columns[-1] + width + 110)
    place(root, columns, 168)
    canvas = Image.new("RGB", (columns[-1] + widths[-1] + 54, root.height + 266), BACKGROUND)
    draw = ImageDraw.Draw(canvas)
    title = "Wasmline | Technical Mind Map" if language == "en" else "Wasmline | 技术思维导图"
    subtitle = (
        "Core Wasm · Component Model · signed packages · AOT catalog · Maven releases"
        if language == "en"
        else "Core Wasm · Component Model · 签名包 · AOT catalog · Maven 发行"
    )
    draw.text((54, 35), title, font=fonts[0], fill="#edf4ff", anchor="lt")
    draw.text((54, 100), subtitle, font=fonts[3], fill="#a4b5cb", anchor="lt")
    draw_connections(draw, root)
    draw_labels(draw, root)
    footer = (
        f"Sources: docs/design-mind.md | {RENDERER_PATH}"
        if language == "en"
        else f"来源：docs/design-mind.md | {RENDERER_PATH}"
    )
    draw.text((54, canvas.height - 54), footer, font=fonts[3], fill="#a4b5cb", anchor="lt")
    metadata = PngImagePlugin.PngInfo()
    metadata.add_text("Title", title)
    metadata.add_text("Description", "Wasmline runtime, tooling, and release contracts summarized in docs/design-mind.md")
    output = REPOSITORY_ROOT / "fumadocs/public/images" / f"wasmline_mind_{language}.png"
    canvas.save(output, pnginfo=metadata, optimize=True)
    print(f"{output.relative_to(REPOSITORY_ROOT)}: {canvas.width} x {canvas.height} PNG")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--font", type=Path, default=Path("/usr/share/fonts/noto-cjk/NotoSansCJK-Regular.ttc"))
    parser.add_argument("--bold-font", type=Path, default=Path("/usr/share/fonts/noto-cjk/NotoSansCJK-Bold.ttc"))
    args = parser.parse_args()
    for language in ("en", "zh"):
        render(language, args.font, args.bold_font)


if __name__ == "__main__":
    main()
