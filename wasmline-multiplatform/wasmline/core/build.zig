const std = @import("std");
const builtin = @import("builtin");

// ============================================================================
// 1. 项目结构定义
// ============================================================================
const ROOT_OFFSET = "../../..";

pub fn build(b: *std.Build) !void {
    const target = b.standardTargetOptions(.{});
    const optimize = b.standardOptimizeOption(.{});

    // 1. 获取项目根目录
    const build_root_path = b.build_root.path.?;
    const raw_root_path = b.pathJoin(&.{ build_root_path, ROOT_OFFSET });
    const repo_root = try std.fs.path.resolve(b.allocator, &.{raw_root_path});

    // 2. 定位依赖 (Input)
    // 这里的 platform_subdir 仍然包含 os/arch (例如 mac/aarch64)，用于找到 platforms 里的库
    const platform_subdir = try getPlatformSubdir(b, target);
    const wasmtime_dir = b.pathJoin(&.{ repo_root, "platforms", platform_subdir });
    const core_dir = b.pathJoin(&.{ repo_root, "wasmline-core" });

    const java_home = try autoDetectJavaHome(b, target);

    // ========================================================================
    // 打印调试信息
    // ========================================================================
    std.debug.print("\n[Debug] Repo Root: {s}\n", .{repo_root});
    std.debug.print("[Config] Input Lib Dir: {s}\n", .{wasmtime_dir});

    // ========================================================================
    // 3. 定义动态库
    // ========================================================================
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

    const cpp_flags = &.{"-std=c++17"};

    // ========================================================================
    // 4. 添加源码
    // ========================================================================

    // 4.1 本地 JNI
    lib.addCSourceFile(.{ .file = b.path("native/WasmlineJni.cpp"), .flags = cpp_flags });

    // 4.2 Desktop 适配 (ConsoleLogger, JniHostHandler)
    lib.addCSourceFile(.{ .file = b.path("src/jvmMain/native/ConsoleLogger.cpp"), .flags = cpp_flags });
    lib.addCSourceFile(.{ .file = b.path("src/jniMain/native/JniHostHandler.cpp"), .flags = cpp_flags });

    // 4.3 外部 Core 源码
    const external_sources: []const []const u8 = &.{
        "src/extensions/FileUtils.cpp",
        "src/Api.cpp",
        "src/Engine.cpp",
        "src/Module.cpp",
        "src/Session.cpp",
    };

    for (external_sources) |src| {
        const full_src_path = b.pathJoin(&.{ core_dir, src });
        lib.addCSourceFile(.{ .file = .{ .cwd_relative = full_src_path }, .flags = cpp_flags });
    }

    // ========================================================================
    // 5. 头文件路径
    // ========================================================================
    lib.addIncludePath(b.path("native"));
    lib.addIncludePath(b.path("src/jniMain/native"));
    lib.addIncludePath(b.path("src/jvmMain/native")); // ConsoleLogger 可能在这里

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

    // ========================================================================
    // 6. 链接
    // ========================================================================
    const lib_name = if (target.result.os.tag == .windows) "wasmtime.lib" else "libwasmtime.a";
    const lib_path = b.pathJoin(&.{ wasmtime_dir, "lib", lib_name });

    std.fs.cwd().access(lib_path, .{}) catch {
        std.debug.print("Error: Library file not found at {s}\n", .{lib_path});
        return error.FileNotFound;
    };
    lib.addObjectFile(.{ .cwd_relative = lib_path });

    if (target.result.os.tag != .windows) {
        lib.linkSystemLibrary("m");
        lib.linkSystemLibrary("dl");
        lib.linkSystemLibrary("pthread");
    }

    // ========================================================================
    // 7. 安装产物 (Output)
    // ========================================================================

    // 计算纯净的输出目录名称 (只包含架构名，不含 mac/linux 等)
    const install_subdir = if (target.result.abi == .android)
        // Android 保持标准命名习惯
        switch (target.result.cpu.arch) {
            .aarch64 => "arm64-v8a",
            .x86_64 => "x86_64",
            .arm => "armeabi-v7a",
            else => "unknown",
        }
    else
        // Desktop: 只要架构名，不要 OS 名
        switch (target.result.cpu.arch) {
            .aarch64 => "aarch64",
            .x86_64 => "x86_64", // 或者用 "x64" 看你喜好，Zig 默认通常是 x86_64
            else => "unknown",
        };

    std.debug.print("[Config] Output Dir:    zig-out/{s}\n", .{install_subdir});

    const install = b.addInstallArtifact(lib, .{
        .dest_dir = .{ .override = .{ .custom = install_subdir } },
    });

    b.getInstallStep().dependOn(&install.step);
}

// ============================================================================
// 辅助函数
// ============================================================================

fn autoDetectJavaHome(b: *std.Build, target: std.Build.ResolvedTarget) ![]const u8 {
    if (b.option([]const u8, "java-home", "Override JAVA_HOME")) |path| {
        return path;
    }
    if (target.result.os.tag == .macos) {
        const result = std.process.Child.run(.{
            .allocator = b.allocator, .argv = &.{"/usr/libexec/java_home"},
        }) catch return error.JavaHomeNotFound;
        return std.mem.trim(u8, result.stdout, " \n\r");
    }
    if (target.result.os.tag == .linux) {
        const common_paths: []const []const u8 = &.{
            "/usr/lib/jvm/default-java",
            "/usr/lib/jvm/java-17-openjdk-amd64",
            "/usr/lib/jvm/java-11-openjdk-amd64",
        };
        for (common_paths) |path| {
            std.fs.cwd().access(path, .{}) catch continue;
            return path;
        }
    }
    if (std.posix.getenv("JAVA_HOME")) |h| return h;
    return error.JavaHomeNotFound;
}

// 这个函数仅用于【寻找 Input 依赖】，保持原来的逻辑不动，以免找不到 platforms 里的库
fn getPlatformSubdir(b: *std.Build, target: std.Build.ResolvedTarget) ![]const u8 {
    const t = target.result;
    if (t.abi == .android) {
        const arch = switch (t.cpu.arch) {
            .aarch64 => "arm64-v8a", .x86_64 => "x86_64", .arm => "armeabi-v7a", else => return error.UnsupportedArch,
        };
        return b.fmt("android/{s}", .{arch});
    }
    switch (t.os.tag) {
        .macos => {
            const arch = switch (t.cpu.arch) { .aarch64 => "aarch64", .x86_64 => "x86_64", else => return error.UnsupportedArch };
            return b.fmt("mac/{s}", .{arch});
        },
        .windows => {
            const arch = switch (t.cpu.arch) { .x86_64 => "x64", .aarch64 => "arm64", else => return error.UnsupportedArch };
            return b.fmt("windows/{s}", .{arch});
        },
        .linux => {
            const arch = switch (t.cpu.arch) { .x86_64 => "x86_64", .aarch64 => "aarch64", else => return error.UnsupportedArch };
            return b.fmt("linux/{s}", .{arch});
        },
        else => return error.UnsupportedOs,
    }
}