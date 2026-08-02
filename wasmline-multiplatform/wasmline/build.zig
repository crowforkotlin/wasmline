const std = @import("std");
const builtin = @import("builtin");
const zcc = @import("compile_commands");

// ============================================================================
// 1. Constants & Configuration
// ============================================================================
const ROOT_OFFSET = "../..";

// Must explicitly specify type as slice to allow iteration in loops
const CPP_FLAGS: []const []const u8 = &.{
    "-std=c++17",
    "-DLIBWASM_STATIC", // Control core wasm.h
    "-DWASI_API_EXTERN=", // Force WASI export macro to empty
    "-DWASM_API_EXTERN=", // Backup: Force Core export macro to empty
};

const EXTERNAL_SOURCES: []const []const u8 = &.{
    "src/api/Api.cpp",
    "src/runtime/Component.cpp",
    "src/runtime/ComponentSession.cpp",
    "src/runtime/Engine.cpp",
    "src/runtime/Module.cpp",
    "src/runtime/RawModuleSession.cpp",
    "src/runtime/Session.cpp",
    "src/value/ComponentValue.cpp",
    "src/invocation/InvocationResult.cpp",
    "src/invocation/TypedInvocationCodec.cpp",
    "src/protocol/WasmlineProtocol.cpp",
    "src/io/FileIO.cpp",
    "src/wasmtime/WasmtimeMessage.cpp",
    "src/wasi/WasiConfig.cpp",
};

// ============================================================================
// 2. Main Build Entry
// ============================================================================
pub fn build(b: *std.Build) !void {
    const target = b.standardTargetOptions(.{});
    const optimize = b.standardOptimizeOption(.{ .preferred_optimize_mode = .ReleaseSmall });

    // 1. Resolve project root directory
    const build_root_path = b.build_root.path.?;
    const raw_root_path = b.pathJoin(&.{ build_root_path, ROOT_OFFSET });
    const repo_root = try std.fs.path.resolve(b.allocator, &.{raw_root_path});

    // 2. Locate dependencies (Inputs)
    const platform_subdir = try getPlatformSubdir(b, target);
    const wasmtime_version = b.option([]const u8, "wasmtime-version", "Wasmtime release tag (e.g. release-v45.0.5)") orelse
        try readWasmtimeVersion(b, repo_root);
    const wasmtime_variant = b.option([]const u8, "wasmtime-variant", "Engine variant (pulley or cranelift)") orelse "pulley";
    const wasmtime_dir = b.pathJoin(&.{ repo_root, "build", "platforms", wasmtime_version, wasmtime_variant, platform_subdir });
    const core_dir = b.pathJoin(&.{ repo_root, "wasmline-core" });
    const java_home = try autoDetectJavaHome(b, target);

    // Print debug info
    std.debug.print("\n[Debug] Repo Root: {s}\n", .{repo_root});
    std.debug.print("[Config] Wasmtime Version: {s}\n", .{wasmtime_version});
    std.debug.print("[Config] Wasmtime Variant: {s}\n", .{wasmtime_variant});
    std.debug.print("[Config] Input Lib Dir: {s}\n", .{wasmtime_dir});

    // 3. Define Dynamic Library (libwasmline — bridge + wasmtime engine, single shared lib)
    const lib = createDynamicLibrary(b, target, optimize);

    // 4. Configure Optimization (Release settings)
    configureOptimization(lib, target, optimize);

    // 5. Add Sources
    try addSourceFiles(b, lib, core_dir);

    // 6. Add Include Paths
    addIncludePaths(b, lib, core_dir, wasmtime_dir, java_home);

    // 7. Link wasmtime static library into libwasmline (whole-archive to export all symbols)
    linkWasmtimeStatic(b, lib, wasmtime_dir, target);

    // 8. Link system dependencies (m, dl, pthread, etc.)
    try linkSystemDependencies(b, lib, target);

    // 9. Install single artifact: libwasmline (contains wasmtime engine)
    try installArtifacts(b, lib, target);

    // 10. Integrate compilation database generation (for editor tooling like clangd/marksman)
    var compile_steps_to_include: std.ArrayList(*std.Build.Step.Compile) = .empty;
    try compile_steps_to_include.append(b.allocator, lib);
    const cdb_internal_step = zcc.createStep(b, "generate_compile_commands_internal", compile_steps_to_include.items);
    b.step("cdb", "Generate compile_commands.json for editor integration.").dependOn(cdb_internal_step);
    b.getInstallStep().dependOn(cdb_internal_step);
}

// ============================================================================
// 3. Helper Functions
// ============================================================================

fn createDynamicLibrary(b: *std.Build, target: std.Build.ResolvedTarget, optimize: std.builtin.OptimizeMode) *std.Build.Step.Compile {
    const lib = b.addLibrary(.{
        .name = "wasmline",
        .root_module = b.createModule(.{
            .target = target,
            .optimize = optimize,
            .link_libc = true,
            .link_libcpp = true,
        }),
        .linkage = .dynamic,
        .use_lld = if (target.result.os.tag == .windows and optimize != .Debug) true else null,
    });
    lib.bundle_compiler_rt = true; // Include compiler runtime
    return lib;
}

fn configureOptimization(lib: *std.Build.Step.Compile, target: std.Build.ResolvedTarget, optimize: std.builtin.OptimizeMode) void {
    const is_release = optimize != .Debug;
    if (is_release) {
        // Strip symbol table to reduce size
        lib.root_module.strip = is_release;

        // Automatically remove unused code sections (equivalent to -dead_strip / --gc-sections)
        lib.link_gc_sections = true;

        // Place functions/data in separate sections for precise removal
        lib.link_function_sections = true;
        lib.link_data_sections = true;

        // Discard local symbols (equivalent to macOS -x)
        lib.discard_local_symbols = true;
    }

    // Enable LTO and force LLD linker for Windows Release builds
    if (target.result.os.tag == .windows and is_release) {
        lib.lto = .full;
    }
}

fn addSourceFiles(b: *std.Build, lib: *std.Build.Step.Compile, core_dir: []const u8) !void {
    // Native JNI
    lib.root_module.addCSourceFile(.{ .file = b.path("src/jniMain/native/WasmlineJni.cpp"), .flags = CPP_FLAGS });

    // Desktop Adapter (ConsoleLogger, JniHostHandler)
    lib.root_module.addCSourceFile(.{ .file = b.path("src/jvmMain/native/ConsoleLogger.cpp"), .flags = CPP_FLAGS });
    lib.root_module.addCSourceFile(.{ .file = b.path("src/jniMain/native/JniHostHandler.cpp"), .flags = CPP_FLAGS });

    // External Core Sources
    for (EXTERNAL_SOURCES) |src| {
        const full_src_path = b.pathJoin(&.{ core_dir, src });
        lib.root_module.addCSourceFile(.{ .file = .{ .cwd_relative = full_src_path }, .flags = CPP_FLAGS });
    }
}

fn addIncludePaths(
    b: *std.Build,
    lib: *std.Build.Step.Compile,
    core_dir: []const u8,
    wasmtime_dir: []const u8,
    java_home: []const u8,
) void {
    lib.root_module.addIncludePath(b.path("src/jniMain/native"));
    lib.root_module.addIncludePath(b.path("src/jvmMain/native")); // ConsoleLogger might be here

    lib.root_module.addIncludePath(.{ .cwd_relative = b.pathJoin(&.{ core_dir, "include" }) });
    lib.root_module.addIncludePath(.{ .cwd_relative = b.pathJoin(&.{ core_dir, "src" }) });
    lib.root_module.addIncludePath(.{ .cwd_relative = b.pathJoin(&.{ wasmtime_dir, "include" }) });
    lib.root_module.addIncludePath(.{ .cwd_relative = b.pathJoin(&.{ java_home, "include" }) });

    // For cross-compilation, the host JDK only has jni_md.h for the host platform.
    // jni_md.h defines primitive typedefs (jint, jlong) that are identical across
    // platforms, so we always use the host's platform-specific directory.
    const host_plat_dir = switch (builtin.os.tag) {
        .linux => "linux",
        .windows => "win32",
        .macos => "darwin",
        else => "linux",
    };
    lib.root_module.addIncludePath(.{ .cwd_relative = b.pathJoin(&.{ java_home, "include", host_plat_dir }) });
}

/// Link the static libwasmtime.a directly into libwasmline so that a single
/// shared library contains both the bridge code and the wasmtime engine.
fn linkWasmtimeStatic(
    b: *std.Build,
    lib: *std.Build.Step.Compile,
    wasmtime_dir: []const u8,
    target: std.Build.ResolvedTarget,
) void {
    const lib_name = if (target.result.os.tag == .windows) blk: {
        if (target.result.abi == .gnu) break :blk "libwasmtime.a";
        break :blk "wasmtime.lib";
    } else "libwasmtime.a";

    const lib_path = b.pathJoin(&.{ wasmtime_dir, "lib", lib_name });
    std.debug.print("[Debug] Wasmtime static lib: {s}\n", .{lib_path});
    lib.root_module.addObjectFile(.{ .cwd_relative = lib_path });
}

/// Link system-level dependencies required by both the bridge code and the
/// statically-linked wasmtime engine (Rust runtime deps, math, threading, etc.).
fn linkSystemDependencies(b: *std.Build, lib: *std.Build.Step.Compile, target: std.Build.ResolvedTarget) !void {
    if (target.result.os.tag == .windows) {
        if (target.result.abi == .gnu) {
            const mingw_path = b.graph.environ_map.get("MINGW_PATH") orelse {
                printMingwRequirements();
                return error.MingwPathNotSet;
            };
            const mingw_lib = findMingwLibraryDir(b, mingw_path, target) orelse {
                std.debug.print(
                    "[Error] MINGW_PATH does not contain libmingwex.a: {s}\n",
                    .{mingw_path},
                );
                printMingwRequirements();
                return error.MingwRuntimeNotFound;
            };
            std.debug.print("[Config] MinGW library dir: {s}\n", .{mingw_lib});
            lib.root_module.addLibraryPath(.{ .cwd_relative = mingw_lib });

            // MinGW's compatibility runtime provides the math, wide-character,
            // and POSIX helper symbols used by the statically-linked zigc/Rust code.
            lib.root_module.addObjectFile(.{
                .cwd_relative = b.pathJoin(&.{ mingw_lib, "libmingwex.a" }),
            });
        }
        lib.root_module.linkSystemLibrary("bcrypt", .{}); // Encryption API (Required for RNG)
        lib.root_module.linkSystemLibrary("userenv", .{}); // User Environment (Env vars)
        lib.root_module.linkSystemLibrary("ole32", .{}); // COM Library
        lib.root_module.linkSystemLibrary("uuid", .{}); // UUID Library
    } else {
        lib.root_module.linkSystemLibrary("m", .{});
        lib.root_module.linkSystemLibrary("dl", .{});
        lib.root_module.linkSystemLibrary("pthread", .{});
    }
}

fn findMingwLibraryDir(
    b: *std.Build,
    mingw_path: []const u8,
    target: std.Build.ResolvedTarget,
) ?[]const u8 {
    const target_triplet = switch (target.result.cpu.arch) {
        .x86_64 => "x86_64-w64-mingw32",
        .x86 => "i686-w64-mingw32",
        .aarch64 => "aarch64-w64-mingw32",
        else => return null,
    };
    const homebrew_toolchain = switch (target.result.cpu.arch) {
        .x86_64 => "toolchain-x86_64",
        .x86 => "toolchain-i686",
        .aarch64 => "toolchain-aarch64",
        else => return null,
    };

    // Supported roots:
    //   Arch/Ubuntu/MSYS2: /usr/x86_64-w64-mingw32
    //   Standalone/llvm-mingw: /path/to/mingw/x86_64-w64-mingw32
    //   Homebrew: /opt/homebrew/opt/mingw-w64/toolchain-x86_64/x86_64-w64-mingw32
    const candidates = [_][]const u8{
        b.pathJoin(&.{ mingw_path, "lib" }),
        b.pathJoin(&.{ mingw_path, target_triplet, "lib" }),
        b.pathJoin(&.{ mingw_path, homebrew_toolchain, target_triplet, "lib" }),
    };

    for (candidates) |candidate| {
        const runtime_path = b.pathJoin(&.{ candidate, "libmingwex.a" });
        std.Io.Dir.cwd().access(b.graph.io, runtime_path, .{}) catch continue;
        return candidate;
    }
    return null;
}

fn printMingwRequirements() void {
    std.debug.print(
        \\
        \\## Windows GNU cross-compile requirements
        \\1. **Install:** MinGW-w64 or llvm-mingw.
        \\2. **Environment Variable:** Set `MINGW_PATH` to the toolchain root.
        \\   Arch/Ubuntu: export MINGW_PATH=/usr/x86_64-w64-mingw32
        \\   llvm-mingw:  export MINGW_PATH=/path/to/llvm-mingw
        \\   Homebrew:   export MINGW_PATH=$(brew --prefix mingw-w64)
        \\
    , .{});
}

/// Read wasmtime version from scripts/versions.json.
fn readWasmtimeVersion(b: *std.Build, repo_root: []const u8) ![]const u8 {
    const versions_path = b.pathJoin(&.{ repo_root, "scripts", "versions.json" });
    const content = std.Io.Dir.cwd().readFileAlloc(b.graph.io, versions_path, b.allocator, .limited(1024 * 1024)) catch {
        std.debug.print("[Warn] Could not open {s}, using default version\n", .{versions_path});
        return "release-v45.0.5";
    };
    defer b.allocator.free(content);
    // Simple JSON parsing: find "wasmtime_version": "X.Y.Z"
    const needle = "\"wasmtime_version\"";
    if (std.mem.indexOf(u8, content, needle)) |idx| {
        const after_key = content[idx + needle.len ..];
        // Skip to the value string
        if (std.mem.indexOf(u8, after_key, "\"")) |v1| {
            const value_start = after_key[v1 + 1 ..];
            if (std.mem.indexOf(u8, value_start, "\"")) |v2| {
                const version = value_start[0..v2];
                return b.fmt("release-v{s}", .{version});
            }
        }
    }
    return "release-v45.0.5";
}

fn installArtifacts(b: *std.Build, lib: *std.Build.Step.Compile, target: std.Build.ResolvedTarget) !void {
    // Calculate output directory name (architecture only)
    const install_subdir = if (target.result.abi == .android)
        switch (target.result.cpu.arch) {
            .aarch64 => "arm64-v8a",
            .x86_64 => "x86_64",
            .arm => "armeabi-v7a",
            else => "unknown",
        }
    else switch (target.result.cpu.arch) {
        .aarch64 => "jni/aarch64",
        .x86_64 => "jni/x86_64",
        else => b.fmt("jni/{s}", .{@tagName(target.result.cpu.arch)}),
    };

    std.debug.print("[Config] Output Dir:    zig-out/{s}\n", .{install_subdir});

    const ext = switch (target.result.os.tag) {
        .windows => ".dll",
        .macos => ".dylib",
        else => ".so",
    };

    // Install single artifact: libwasmline (contains wasmtime engine)
    const wasmline_name = b.fmt("libwasmline{s}", .{ext});
    const install_wasmline = b.addInstallFileWithDir(lib.getEmittedBin(), .{ .custom = install_subdir }, wasmline_name);
    b.getInstallStep().dependOn(&install_wasmline.step);
}

// ============================================================================
// 4. Utils
// ============================================================================

fn autoDetectJavaHome(b: *std.Build, target: std.Build.ResolvedTarget) ![]const u8 {
    if (b.option([]const u8, "java-home", "Override JAVA_HOME")) |path| {
        return validateJavaHomeWithContext(b, path, target, "-Djava-home");
    }

    if (b.graph.environ_map.get("JAVA_HOME")) |path| {
        if (tryValidateJavaHome(b, path, target, "JAVA_HOME")) |valid| return valid;
    }

    if (target.result.os.tag == .macos) {
        const result = std.process.run(b.allocator, b.graph.io, .{ .argv = &.{"/usr/libexec/java_home"} }) catch |err| {
            std.debug.print("[Warn] Failed to execute /usr/libexec/java_home: {any}\n", .{err});
            if (try detectJavaHomeFromJavaCommand(b, target)) |path| return path;
            return detectJavaHomeFromShellConfigs(b, target);
        };
        defer b.allocator.free(result.stdout);
        defer b.allocator.free(result.stderr);
        switch (result.term) {
            .exited => |code| if (code == 0) {
                const path = std.mem.trim(u8, result.stdout, " \n\r");
                if (path.len != 0) {
                    if (tryValidateJavaHome(b, path, target, "/usr/libexec/java_home")) |valid| return valid;
                }
            } else {
                const stderr_text = std.mem.trim(u8, result.stderr, " \n\r");
                if (stderr_text.len != 0) {
                    std.debug.print("[Warn] /usr/libexec/java_home returned: {s}\n", .{stderr_text});
                }
            },
            else => std.debug.print("[Warn] /usr/libexec/java_home did not exit normally.\n", .{}),
        }
    }

    if (try detectJavaHomeFromJavaCommand(b, target)) |path| return path;
    return detectJavaHomeFromShellConfigs(b, target);
}

fn getPlatformSubdir(b: *std.Build, target: std.Build.ResolvedTarget) ![]const u8 {
    const t = target.result;
    if (t.abi == .android) {
        const arch = switch (t.cpu.arch) {
            .aarch64 => "arm64-v8a",
            .x86_64 => "x86_64",
            .arm => "armeabi-v7a",
            else => return error.UnsupportedArch,
        };
        return b.fmt("android/{s}", .{arch});
    }
    switch (t.os.tag) {
        .macos => {
            const arch = switch (t.cpu.arch) {
                .aarch64 => "aarch64",
                .x86_64 => "x64",
                else => return error.UnsupportedArch,
            };
            return b.fmt("mac/{s}", .{arch});
        },
        .windows => {
            const arch = switch (t.cpu.arch) {
                .x86_64 => "x64",
                .aarch64 => "arm64",
                else => return error.UnsupportedArch,
            };
            return b.fmt("windows/{s}", .{arch});
        },
        .linux => {
            const arch = switch (t.cpu.arch) {
                .x86_64 => "x64",
                .aarch64 => "aarch64",
                else => return error.UnsupportedArch,
            };
            return b.fmt("linux/{s}", .{arch});
        },
        else => return error.UnsupportedOs,
    }
}

fn validateJavaHome(b: *std.Build, java_home: []const u8, target: std.Build.ResolvedTarget) ![]const u8 {
    // Always require jni.h (platform-independent)
    const jni_header = try std.fs.path.join(std.heap.page_allocator, &.{ java_home, "include", "jni.h" });
    defer std.heap.page_allocator.free(jni_header);
    std.Io.Dir.cwd().access(b.graph.io, jni_header, .{}) catch return error.JavaHomeInvalid;

    // For cross-compilation, the host JDK may not have the target's jni_md.h
    // (e.g. Linux JDK has include/linux/jni_md.h but not include/darwin/jni_md.h).
    // jni_md.h only defines primitive typedefs (jint, jlong) which are identical
    // across platforms, so we accept any available jni_md.h as valid.
    const host_os = builtin.os.tag;
    const target_os = target.result.os.tag;
    const is_cross = host_os != target_os;

    if (!is_cross) {
        // Native build: require exact platform jni_md.h
        const jni_platform_dir = switch (target_os) {
            .linux => "linux",
            .windows => "win32",
            .macos => "darwin",
            else => return error.UnsupportedOs,
        };
        const jni_platform_header = try std.fs.path.join(std.heap.page_allocator, &.{ java_home, "include", jni_platform_dir, "jni_md.h" });
        defer std.heap.page_allocator.free(jni_platform_header);
        std.Io.Dir.cwd().access(b.graph.io, jni_platform_header, .{}) catch return error.JavaHomeInvalid;
    }
    // Cross-compilation: jni.h check is sufficient

    return java_home;
}

fn validateJavaHomeWithContext(b: *std.Build, java_home: []const u8, target: std.Build.ResolvedTarget, source: []const u8) ![]const u8 {
    return validateJavaHome(b, java_home, target) catch |err| {
        switch (err) {
            error.JavaHomeInvalid => {
                const jni_platform_dir = switch (target.result.os.tag) {
                    .linux => "linux",
                    .windows => "win32",
                    .macos => "darwin",
                    else => return error.UnsupportedOs,
                };

                const jni_header = try std.fs.path.join(std.heap.page_allocator, &.{ java_home, "include", "jni.h" });
                defer std.heap.page_allocator.free(jni_header);
                const jni_platform_header = try std.fs.path.join(std.heap.page_allocator, &.{ java_home, "include", jni_platform_dir, "jni_md.h" });
                defer std.heap.page_allocator.free(jni_platform_header);

                std.debug.print(
                    "[Error] Invalid Java home from {s}: {s}\n[Error] Expected JNI headers:\n  - {s}\n  - {s}\n",
                    .{ source, java_home, jni_header, jni_platform_header },
                );
            },
            else => {},
        }
        return err;
    };
}

fn tryValidateJavaHome(b: *std.Build, java_home: []const u8, target: std.Build.ResolvedTarget, source: []const u8) ?[]const u8 {
    return validateJavaHome(b, java_home, target) catch |err| {
        switch (err) {
            error.JavaHomeInvalid => {
                std.debug.print("[Warn] Ignoring invalid Java home from {s}: {s}\n", .{ source, java_home });
                return null;
            },
            else => {
                std.debug.print("[Warn] Failed to validate Java home from {s}: {any}\n", .{ source, err });
                return null;
            },
        }
    };
}

fn detectJavaHomeFromShellConfigs(b: *std.Build, target: std.Build.ResolvedTarget) ![]const u8 {
    const home = b.graph.environ_map.get("HOME") orelse return error.JavaHomeNotFound;
    const config_names = [_][]const u8{ ".zshrc", ".bashrc", ".bash_profile" };

    for (config_names) |config_name| {
        const config_path = try std.fs.path.join(b.allocator, &.{ home, config_name });
        defer b.allocator.free(config_path);

        const content = std.Io.Dir.cwd().readFileAlloc(b.graph.io, config_path, b.allocator, .limited(1024 * 1024)) catch |err| switch (err) {
            error.FileNotFound => continue,
            else => return err,
        };
        defer b.allocator.free(content);

        var candidates: std.ArrayList([]const u8) = .empty;
        defer candidates.deinit(b.allocator);

        try collectQuotedPathCandidates(b.allocator, content, &candidates);
        for (candidates.items) |candidate| {
            if (tryValidateJavaHome(b, candidate, target, config_path)) |valid| {
                std.debug.print("[Info] Using Java home discovered from {s}: {s}\n", .{ config_path, valid });
                return valid;
            }
        }
    }

    return error.JavaHomeNotFound;
}

fn detectJavaHomeFromJavaCommand(b: *std.Build, target: std.Build.ResolvedTarget) !?[]const u8 {
    const result = std.process.run(b.allocator, b.graph.io, .{
        .argv = &.{ "java", "-XshowSettings:properties", "-version" },
    }) catch |err| {
        std.debug.print("[Warn] Failed to execute java -XshowSettings:properties -version: {any}\n", .{err});
        return null;
    };
    defer b.allocator.free(result.stdout);
    defer b.allocator.free(result.stderr);

    switch (result.term) {
        .exited => |code| if (code != 0) {
            const stderr_text = std.mem.trim(u8, result.stderr, " \n\r");
            if (stderr_text.len != 0) {
                std.debug.print("[Warn] java -XshowSettings:properties -version returned: {s}\n", .{stderr_text});
            }
            return null;
        },
        else => {
            std.debug.print("[Warn] java -XshowSettings:properties -version did not exit normally.\n", .{});
            return null;
        },
    }

    const detected = extractJavaHomeFromJavaSettings(result.stderr) orelse extractJavaHomeFromJavaSettings(result.stdout) orelse {
        std.debug.print("[Warn] Could not infer java.home from java command output.\n", .{});
        return null;
    };

    const owned_path = try b.allocator.dupe(u8, detected);
    return tryValidateJavaHome(b, owned_path, target, "java -XshowSettings:properties -version");
}

fn extractJavaHomeFromJavaSettings(output: []const u8) ?[]const u8 {
    var lines = std.mem.splitScalar(u8, output, '\n');
    while (lines.next()) |raw_line| {
        const line = std.mem.trim(u8, raw_line, " \t\r");
        if (std.mem.indexOf(u8, line, "java.home = ")) |start| {
            return std.mem.trim(u8, line[start + "java.home = ".len ..], " \t\r");
        }
    }
    return null;
}

fn collectQuotedPathCandidates(
    allocator: std.mem.Allocator,
    content: []const u8,
    candidates: *std.ArrayList([]const u8),
) !void {
    var i: usize = 0;
    while (i < content.len) : (i += 1) {
        const quote = content[i];
        if (quote != '"' and quote != '\'') continue;

        var j = i + 1;
        while (j < content.len and content[j] != quote) : (j += 1) {}
        if (j >= content.len) break;

        const candidate = std.mem.trim(u8, content[i + 1 .. j], " \t\r\n");
        if (looksLikeJavaHome(candidate) and !containsSlice(candidates.items, candidate)) {
            try candidates.append(allocator, try allocator.dupe(u8, candidate));
        }
    }
}

fn looksLikeJavaHome(candidate: []const u8) bool {
    return std.mem.endsWith(u8, candidate, "/Contents/Home") or
        std.mem.endsWith(u8, candidate, "/Home") or
        std.mem.indexOf(u8, candidate, "jbr") != null or
        std.mem.indexOf(u8, candidate, "jdk") != null or
        std.mem.indexOf(u8, candidate, "java") != null;
}

fn containsSlice(items: []const []const u8, needle: []const u8) bool {
    for (items) |item| {
        if (std.mem.eql(u8, item, needle)) return true;
    }
    return false;
}
