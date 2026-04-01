#!/usr/bin/env python3
from __future__ import annotations

import datetime as _dt
import os
from pathlib import Path
import subprocess
import sys
from typing import Iterable

ROOT = Path(__file__).resolve().parents[4]
OUT_DIR = ROOT / ".cache" / "skill"
STAMP = _dt.datetime.now().strftime("%Y%m%d-%H%M%S")
SNAPSHOT = OUT_DIR / f"context-{STAMP}.md"
LATEST = OUT_DIR / "context-latest.md"

EXCLUDED_DIR_NAMES = {
    ".git",
    ".gradle",
    ".idea",
    ".kotlin",
    ".zig-cache",
    ".cxx",
    "build",
    "node_modules",
    ".cache",
}

KEY_FILES = [
    ".github/skills/wasmline/SKILL.md",
    "README_zh.md",
    "README.md",
    "scripts/init.sh",
    "wasmline-multiplatform/settings.gradle.kts",
    "wasmline-multiplatform/gradle.properties",
    "wasmline-multiplatform/wasmline/build.gradle.kts",
    "wasmline-multiplatform/wasmline-kotlin-plugin/build.gradle.kts",
    "wasmline-multiplatform/wasmline-kotlin-plugin/testData/box/README_zh.md",
    ".github/plans/ir-plan.md",
    "wasmline-core/src/Engine.cpp",
    "wasmline-multiplatform/wasmline/src/commonMain/kotlin/crow/wasmline/internal/bridge/GeneratedBridge.kt",
    "wasmline-multiplatform/wasmline/src/commonTest/kotlin/crow/wasmline/WasmlineServiceRuntimeTest.kt",
]


def rel(path: Path) -> str:
    return path.relative_to(ROOT).as_posix()


def run_git(*args: str) -> str:
    try:
        result = subprocess.run(
            ["git", "--no-pager", *args],
            cwd=ROOT,
            check=False,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
    except FileNotFoundError:
        return "<git unavailable>"
    output = (result.stdout or result.stderr).strip()
    return output or "<empty>"


def language_for(path: Path) -> str:
    suffix = path.suffix.lower()
    return {
        ".md": "markdown",
        ".kt": "kotlin",
        ".kts": "kotlin",
        ".sh": "bash",
        ".py": "python",
        ".cpp": "cpp",
        ".h": "cpp",
        ".java": "java",
        ".properties": "ini",
    }.get(suffix, "text")


def read_excerpt(path: Path, limit: int = 80) -> str:
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except UnicodeDecodeError:
        return "<binary or non-utf8 file>"
    excerpt = lines[:limit]
    if len(lines) > limit:
        excerpt.append("...<truncated>...")
    return "\n".join(excerpt)


def walk_with_depth(base: Path, max_depth: int) -> list[Path]:
    results: list[Path] = []
    stack: list[tuple[Path, int]] = [(base, 0)]
    while stack:
        current, depth = stack.pop()
        if depth > max_depth:
            continue
        if current != base:
            results.append(current)
        if current.is_dir() and depth < max_depth and current.name not in EXCLUDED_DIR_NAMES:
            try:
                children = sorted(current.iterdir(), key=lambda p: (not p.is_dir(), p.name.lower()), reverse=False)
            except PermissionError:
                continue
            for child in reversed(children):
                if child.name in EXCLUDED_DIR_NAMES:
                    continue
                stack.append((child, depth + 1))
    return sorted(results, key=lambda p: rel(p))


def list_gradle_modules() -> list[str]:
    base = ROOT / "wasmline-multiplatform"
    modules: list[str] = []
    for path in sorted(base.rglob("build.gradle.kts")):
        if any(part in EXCLUDED_DIR_NAMES for part in path.parts):
            continue
        modules.append(rel(path.parent))
    return modules


def indent_tree(paths: Iterable[Path], base: Path) -> str:
    lines = []
    for path in sorted(paths, key=lambda p: rel(p)):
        relative = path.relative_to(base)
        depth = len(relative.parts) - 1
        label = f"{'  ' * depth}- {relative.parts[-1]}{'/' if path.is_dir() else ''}"
        lines.append(label)
    return "\n".join(lines)


def changed_files() -> list[str]:
    out = run_git("diff", "--name-only")
    if out in {"<empty>", "<git unavailable>"}:
        return []
    return [line.strip() for line in out.splitlines() if line.strip()]


def staged_or_untracked_files() -> list[str]:
    out = run_git("status", "--short")
    if out in {"<empty>", "<git unavailable>"}:
        return []
    files = []
    for line in out.splitlines():
        entry = line[3:].strip() if len(line) > 3 else line.strip()
        if entry:
            files.append(entry)
    return files


def write_snapshot() -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    top_paths = walk_with_depth(ROOT, max_depth=2)
    module_paths = walk_with_depth(ROOT / "wasmline-multiplatform", max_depth=2)
    gradle_modules = list_gradle_modules()
    changed = changed_files()
    status_files = staged_or_untracked_files()

    lines: list[str] = []
    lines.append("# Wasmline Context Snapshot")
    lines.append("")
    lines.append(f"- Generated at: {_dt.datetime.now().isoformat(timespec='seconds')}")
    lines.append(f"- Repository root: `{ROOT}`")
    lines.append("")

    lines.append("## Git 概览")
    lines.append("")
    lines.append("```text")
    lines.append(run_git("rev-parse", "--abbrev-ref", "HEAD"))
    lines.append(run_git("rev-parse", "HEAD"))
    lines.append("```")
    lines.append("")
    lines.append("### 工作区状态")
    lines.append("")
    lines.append("```text")
    lines.append(run_git("status", "--short"))
    lines.append("```")
    lines.append("")
    lines.append("### Diff 统计")
    lines.append("")
    lines.append("```text")
    lines.append(run_git("diff", "--stat"))
    lines.append("```")
    lines.append("")

    lines.append("## 根目录结构（深度 2）")
    lines.append("")
    lines.append("```text")
    lines.append(indent_tree(top_paths, ROOT))
    lines.append("```")
    lines.append("")

    lines.append("## `wasmline-multiplatform/` 结构（深度 2）")
    lines.append("")
    lines.append("```text")
    lines.append(indent_tree(module_paths, ROOT / "wasmline-multiplatform"))
    lines.append("```")
    lines.append("")

    lines.append("## Gradle 模块清单")
    lines.append("")
    for module in gradle_modules:
        lines.append(f"- `{module}`")
    lines.append("")

    lines.append("## 关键上下文文件摘录")
    lines.append("")
    for relative in KEY_FILES:
        path = ROOT / relative
        if not path.exists():
            lines.append(f"### `{relative}`")
            lines.append("")
            lines.append("<missing>")
            lines.append("")
            continue
        lines.append(f"### `{relative}`")
        lines.append("")
        lines.append(f"```{language_for(path)}")
        lines.append(read_excerpt(path, limit=80))
        lines.append("```")
        lines.append("")

    lines.append("## 当前改动文件")
    lines.append("")
    if status_files:
        for file in status_files:
            lines.append(f"- `{file}`")
    else:
        lines.append("- <none>")
    lines.append("")

    if changed:
        lines.append("## 变更 diff 预览")
        lines.append("")
        lines.append("```diff")
        diff_preview = run_git("diff", "--unified=2", "--", *changed)
        preview_lines = diff_preview.splitlines()
        if len(preview_lines) > 400:
            preview_lines = preview_lines[:400] + ["...<truncated>..."]
        lines.extend(preview_lines or ["<empty>"])
        lines.append("```")
        lines.append("")

    lines.append("## 建议使用方式")
    lines.append("")
    lines.append("1. 先运行 `bash ./.github/skills/wasmline/scripts/skill_preflight.sh`，确认 JBR 21 与平台资产。")
    lines.append("2. 再运行本脚本，生成一份当前仓库快照。")
    lines.append("3. 每次进入新需求或完成一轮修改后，重新生成快照，重点比对 `当前改动文件` 与 `变更 diff 预览`。")
    lines.append("4. 若问题落在 IR/plugin，请优先回看 `wasmline-kotlin-plugin` 与 `.github/plans/ir-plan.md`。")
    lines.append("")

    content = "\n".join(lines).rstrip() + "\n"
    SNAPSHOT.write_text(content, encoding="utf-8")
    LATEST.write_text(content, encoding="utf-8")

    print(f"Generated: {SNAPSHOT}")
    print(f"Updated : {LATEST}")


if __name__ == "__main__":
    try:
        write_snapshot()
    except KeyboardInterrupt:
        sys.exit(130)
