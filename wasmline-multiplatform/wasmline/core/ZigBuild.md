# Zig Build Instructions

- Zig Version : 0.15.1

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
- https://github.com/niXman/mingw-builds-binaries/releases
- https://github.com/mstorsjo/llvm-mingw/releases

**Build for Windows:**
```shell
zig build -Dtarget=x86_64-windows-gnu --release=small -p src/jvmMain/resources 
```