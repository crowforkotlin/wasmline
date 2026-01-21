# Zig Build Instructions

- Zig Version : 0.15.1

- ```shell
Download .tar.gz
zig fetch --save-exact=compile_commands "https://github.com/the-argus/zig-compile-commands/archive/70fb439897e12cae896c071717d7c9c382918689.tar.gz"
zig fetch --save-exact=compile_commands ~/Downloads/zig-compile-commands-70fb439897e12cae896c071717d7c9c382918689.tar.gz
```

## General Build

**Release (Small size):**

```shell
zig build --release=small -p src/jvmMain/resources
```

**Debug:**

```shellº
zig build -p src/jvmMain/resources
```
---

## Windows Requirements

1. **Install:** MinGW-w64 or llvm-mingw.
2. **Environment Variable:** Set `MINGW_PATH` to your MinGW root directory (e.g., `C:/MingwX64`).

- <https://github.com/niXman/mingw-builds-binaries/releases>
- <https://github.com/mstorsjo/llvm-mingw/releases>

**Build for Windows:**

```shell
zig build -Dtarget=x86_64-windows-gnu --release=small -p src/jvmMain/resources 
```

