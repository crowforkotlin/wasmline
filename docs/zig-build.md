# Zig Build Instructions

- Zig Version : 0.16.0

```shell
# Check Zig version
zig version

# Download compile_commands dependency
zig fetch --save-exact=compile_commands "https://github.com/the-argus/zig-compile-commands/archive/24a28a2b68d2e4351323d646649c9cd3061525d6.tar.gz"
```

## General Build

**Release (Small size):**

```shell
zig build --release=small -p src/jvmMain/resources
```

If Java is not exported as `JAVA_HOME`, the build now falls back to the active `java` command.
You can still override it explicitly with `-Djava-home=/path/to/jdk`.

## Editor Integration

Generate the C/C++ compilation database from this directory:

```shell
zig build cdb
```

The database is generated for the JNI and `wasmline-core` sources and synchronized to
the repository root as `../../compile_commands.json`. `clangd` can then discover it
automatically when editing files under either source tree. The file is machine-local
build output and is ignored by Git.

**Debug:**

```shell
zig build -p src/jvmMain/resources
```
---

## Windows Requirements

1. **Install:** MinGW-w64 or llvm-mingw.
2. **Environment Variable:** Set `MINGW_PATH` to your MinGW root directory (e.g., `C:/MingwX64`).
   On macOS with Homebrew, use `export MINGW_PATH="$(brew --prefix mingw-w64)"`.

- <https://github.com/niXman/mingw-builds-binaries/releases>
- <https://github.com/mstorsjo/llvm-mingw/releases>

**Build for Windows:**

```shell
zig build -Dtarget=x86_64-windows-gnu --release=small -p shared/jvmMain/resources 
```
