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
    "src/extensions/FileUtils.cpp",
    "src/Api.cpp",
    "src/Engine.cpp",
    "src/Module.cpp",
    "src/Session.cpp",
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
    const wasmtime_dir = b.pathJoin(&.{ repo_root, "platforms", platform_subdir });
    const core_dir = b.pathJoin(&.{ repo_root, "wasmline-core" });
    const java_home = try autoDetectJavaHome(b, target);

    // Print debug info
    std.debug.print("\n[Debug] Repo Root: {s}\n", .{repo_root});
    std.debug.print("[Config] Input Lib Dir: {s}\n", .{wasmtime_dir});

    // 3. Define Dynamic Library
    const lib = createDynamicLibrary(b, target, optimize);

    // 4. Configure Optimization (Release settings)
    configureOptimization(lib, target, optimize);

    // 5. Add Sources
    try addSourceFiles(b, lib, core_dir);

    // 6. Add Include Paths
    addIncludePaths(b, lib, core_dir, wasmtime_dir, java_home, target);

    // 7. Link Libraries
    try linkDependencies(b, lib, wasmtime_dir, target);

    // 8. Install Artifacts (Output)
    try installArtifacts(b, lib, target);

    // 9. Integrate compilation database generation (for editor tooling like clangd/marksman)
    var compile_steps_to_include = std.ArrayList(*std.Build.Step.Compile){};
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
        lib.want_lto = true;
        lib.use_lld = true;
    }
}

fn addSourceFiles(b: *std.Build, lib: *std.Build.Step.Compile, core_dir: []const u8) !void {
    // Native JNI
    lib.addCSourceFile(.{ .file = b.path("src/jniMain/native/WasmlineJni.cpp"), .flags = CPP_FLAGS });

    // Desktop Adapter (ConsoleLogger, JniHostHandler)
    lib.addCSourceFile(.{ .file = b.path("src/jvmMain/native/ConsoleLogger.cpp"), .flags = CPP_FLAGS });
    lib.addCSourceFile(.{ .file = b.path("src/jniMain/native/JniHostHandler.cpp"), .flags = CPP_FLAGS });

    // External Core Sources
    for (EXTERNAL_SOURCES) |src| {
        const full_src_path = b.pathJoin(&.{ core_dir, src });
        lib.addCSourceFile(.{ .file = .{ .cwd_relative = full_src_path }, .flags = CPP_FLAGS });
    }
}

fn addIncludePaths(
    b: *std.Build,
    lib: *std.Build.Step.Compile,
    core_dir: []const u8,
    wasmtime_dir: []const u8,
    java_home: []const u8,
    target: std.Build.ResolvedTarget,
) void {
    lib.addIncludePath(b.path("src/jniMain/native"));
    lib.addIncludePath(b.path("src/jniMain/native"));
    lib.addIncludePath(b.path("src/jvmMain/native")); // ConsoleLogger might be here

    lib.addIncludePath(.{ .cwd_relative = b.pathJoin(&.{ core_dir, "include" }) });
    lib.addIncludePath(.{ .cwd_relative = b.pathJoin(&.{ core_dir, "include/extensions" }) });
    lib.addIncludePath(.{ .cwd_relative = b.pathJoin(&.{ wasmtime_dir, "include" }) });
    lib.addIncludePath(.{ .cwd_relative = b.pathJoin(&.{ java_home, "include" }) });

    const jni_plat_dir = switch (target.result.os.tag) {
        .linux => "linux",
        .windows => "win32",
        .macos => "darwin",
        else => "linux",
    };
    lib.addIncludePath(.{ .cwd_relative = b.pathJoin(&.{ java_home, "include", jni_plat_dir }) });
}

fn linkDependencies(b: *std.Build, lib: *std.Build.Step.Compile, wasmtime_dir: []const u8, target: std.Build.ResolvedTarget) !void {
    const lib_name = if (target.result.os.tag == .windows) blk: {
        if (target.result.abi == .gnu) break :blk "libwasmtime.a";
        break :blk "wasmtime.lib";
    } else "libwasmtime.a";

    const lib_path = b.pathJoin(&.{ wasmtime_dir, "lib", lib_name });
    std.debug.print("[Debug] Checking library path: {s}\n", .{lib_path});
    std.fs.cwd().access(lib_path, .{}) catch {
        std.debug.print("Error: Library file not found at {s}\n", .{lib_path});
        return error.FileNotFound;
    };

    lib.addObjectFile(.{ .cwd_relative = lib_path });

    if (target.result.os.tag == .windows) {
        // Enforce MINGW_PATH environment variable
        const mingw_path = std.process.getEnvVarOwned(b.allocator, "MINGW_PATH") catch {
            std.debug.print(
                \\
                \\## Windows Requirements
                \\1. **Install:** MinGW-w64 or llvm-mingw.
                \\2. **Environment Variable:** Set `MINGW_PATH` to your MinGW root directory (e.g., `C:/MingwX64`).
                \\- https://github.com/niXman/mingw-builds-binaries/releases
                \\- https://github.com/mstorsjo/llvm-mingw/releases
                \\
            , .{});
            return error.MingwPathNotFound;
        };
        const mingw_lib = b.pathJoin(&.{ mingw_path, "x86_64-w64-mingw32/lib" });
        lib.addLibraryPath(.{ .cwd_relative = mingw_lib });
        lib.linkSystemLibrary("bcrypt"); // Encryption API (Required for RNG)
        lib.linkSystemLibrary("userenv"); // User Environment (Env vars)
        lib.linkSystemLibrary("ole32"); // COM Library
        lib.linkSystemLibrary("uuid"); // UUID Library
    } else {
        lib.linkSystemLibrary("m");
        lib.linkSystemLibrary("dl");
        lib.linkSystemLibrary("pthread");
    }
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
    const final_name = b.fmt("libwasmline{s}", .{ext});

    const install_action = b.addInstallFileWithDir(lib.getEmittedBin(), .{ .custom = install_subdir }, final_name);

    const lib_dir_path = b.getInstallPath(.prefix, "lib");
    const deleteLib = b.addRemoveDirTree(.{ .cwd_relative = lib_dir_path });
    deleteLib.step.dependOn(&install_action.step);
    b.getInstallStep().dependOn(&deleteLib.step);
}

// ============================================================================
// 4. Utils
// ============================================================================

fn autoDetectJavaHome(b: *std.Build, target: std.Build.ResolvedTarget) ![]const u8 {
    if (b.option([]const u8, "java-home", "Override JAVA_HOME")) |path| {
        return validateJavaHomeWithContext(path, target, "-Djava-home");
    }

    if (std.process.getEnvVarOwned(b.allocator, "JAVA_HOME")) |path| {
        if (tryValidateJavaHome(path, target, "JAVA_HOME")) |valid| return valid;
    } else |_| {}

    if (target.result.os.tag == .macos) {
        const result = std.process.Child.run(.{ .allocator = b.allocator, .argv = &.{"/usr/libexec/java_home"} }) catch |err| {
            std.debug.print("[Warn] Failed to execute /usr/libexec/java_home: {any}\n", .{err});
            if (try detectJavaHomeFromJavaCommand(b, target)) |path| return path;
            return detectJavaHomeFromShellConfigs(b, target);
        };
        if (result.term.Exited == 0) {
            const path = std.mem.trim(u8, result.stdout, " \n\r");
            if (path.len != 0) {
                if (tryValidateJavaHome(path, target, "/usr/libexec/java_home")) |valid| return valid;
            }
        } else {
            const stderr_text = std.mem.trim(u8, result.stderr, " \n\r");
            if (stderr_text.len != 0) {
                std.debug.print("[Warn] /usr/libexec/java_home returned: {s}\n", .{stderr_text});
            }
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

fn validateJavaHome(java_home: []const u8, target: std.Build.ResolvedTarget) ![]const u8 {
    const jni_platform_dir = switch (target.result.os.tag) {
        .linux => "linux",
        .windows => "win32",
        .macos => "darwin",
        else => return error.UnsupportedOs,
    };

    const jni_header = try std.fs.path.join(std.heap.page_allocator, &.{ java_home, "include", "jni.h" });
    defer std.heap.page_allocator.free(jni_header);
    std.fs.cwd().access(jni_header, .{}) catch return error.JavaHomeInvalid;

    const jni_platform_header = try std.fs.path.join(std.heap.page_allocator, &.{ java_home, "include", jni_platform_dir, "jni_md.h" });
    defer std.heap.page_allocator.free(jni_platform_header);
    std.fs.cwd().access(jni_platform_header, .{}) catch return error.JavaHomeInvalid;

    return java_home;
}

fn validateJavaHomeWithContext(java_home: []const u8, target: std.Build.ResolvedTarget, source: []const u8) ![]const u8 {
    return validateJavaHome(java_home, target) catch |err| {
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

fn tryValidateJavaHome(java_home: []const u8, target: std.Build.ResolvedTarget, source: []const u8) ?[]const u8 {
    return validateJavaHome(java_home, target) catch |err| {
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
    const home = std.process.getEnvVarOwned(b.allocator, "HOME") catch return error.JavaHomeNotFound;
    const config_names = [_][]const u8{ ".zshrc", ".bashrc", ".bash_profile" };

    for (config_names) |config_name| {
        const config_path = try std.fs.path.join(b.allocator, &.{ home, config_name });
        defer b.allocator.free(config_path);

        const content = std.fs.cwd().readFileAlloc(b.allocator, config_path, 1024 * 1024) catch |err| switch (err) {
            error.FileNotFound => continue,
            else => return err,
        };
        defer b.allocator.free(content);

        var candidates = std.ArrayList([]const u8){};
        defer candidates.deinit(b.allocator);

        try collectQuotedPathCandidates(b.allocator, content, &candidates);
        for (candidates.items) |candidate| {
            if (tryValidateJavaHome(candidate, target, config_path)) |valid| {
                std.debug.print("[Info] Using Java home discovered from {s}: {s}\n", .{ config_path, valid });
                return valid;
            }
        }
    }

    return error.JavaHomeNotFound;
}

fn detectJavaHomeFromJavaCommand(b: *std.Build, target: std.Build.ResolvedTarget) !?[]const u8 {
    const result = std.process.Child.run(.{
        .allocator = b.allocator,
        .argv = &.{ "java", "-XshowSettings:properties", "-version" },
    }) catch |err| {
        std.debug.print("[Warn] Failed to execute java -XshowSettings:properties -version: {any}\n", .{err});
        return null;
    };
    defer b.allocator.free(result.stdout);
    defer b.allocator.free(result.stderr);

    if (result.term.Exited != 0) {
        const stderr_text = std.mem.trim(u8, result.stderr, " \n\r");
        if (stderr_text.len != 0) {
            std.debug.print("[Warn] java -XshowSettings:properties -version returned: {s}\n", .{stderr_text});
        }
        return null;
    }

    const detected = extractJavaHomeFromJavaSettings(result.stderr) orelse extractJavaHomeFromJavaSettings(result.stdout) orelse {
        std.debug.print("[Warn] Could not infer java.home from java command output.\n", .{});
        return null;
    };

    const owned_path = try b.allocator.dupe(u8, detected);
    return tryValidateJavaHome(owned_path, target, "java -XshowSettings:properties -version");
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
