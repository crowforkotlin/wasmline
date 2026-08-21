#!/usr/bin/env bash

if [[ -n "${WASMLINE_TARGETS_SH_LOADED:-}" ]]; then
  return 0
fi
WASMLINE_TARGETS_SH_LOADED=1

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/paths.sh"

wasmline_jni_targets() {
  local engine="$1"
  local kind="$2"
  python3 - "${WASMLINE_SCRIPTS_ROOT}/config/wasmtime-targets.json" "${engine}" "${kind}" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    targets = json.load(source)["targets"]
engine, kind = sys.argv[2:]
for target in targets:
    jni = target.get("jni")
    if engine not in target["engines"] or not jni or jni["kind"] != kind:
        continue
    if kind == "android":
        print("|".join((jni["abi"], target["installPath"])))
    else:
        print("|".join((
            jni["zigTarget"],
            target["installPath"],
            jni["platform"],
            jni["arch"],
            jni["extension"],
        )))
PY
}

wasmline_kotlin_native_install_path() {
  local target_name="$1"
  local engine="$2"
  python3 - "${WASMLINE_SCRIPTS_ROOT}/config/wasmtime-targets.json" "${target_name}" "${engine}" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    targets = json.load(source)["targets"]
target_name, engine = sys.argv[2:]
for target in targets:
    if target.get("kotlinNativeTarget") == target_name and engine in target["engines"]:
        print(target["installPath"])
        raise SystemExit(0)
raise SystemExit(f"Unsupported Kotlin/Native target and engine: {target_name}/{engine}")
PY
}

wasmline_published_kotlin_native_targets() {
  local engine="$1"
  local host_os="$2"
  python3 - "${WASMLINE_SCRIPTS_ROOT}/config/wasmtime-targets.json" "${engine}" "${host_os}" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    targets = json.load(source)["targets"]
engine, host_os = sys.argv[2:]
for target in targets:
    target_name = target.get("kotlinNativeTarget")
    if not target_name or not target.get("publishedKotlinNative"):
        continue
    if engine not in target["engines"]:
        continue
    if target_name.startswith(("ios", "macos")) and host_os != "Darwin":
        continue
    print("|".join((target_name, target["installPath"])))
PY
}

wasmline_engine_install_paths() {
  local engine="$1"
  python3 - "${WASMLINE_SCRIPTS_ROOT}/config/wasmtime-targets.json" "${engine}" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    targets = json.load(source)["targets"]
engine = sys.argv[2]
for target in targets:
    if engine in target["engines"] and target.get("jni"):
        print(target["installPath"])
PY
}
