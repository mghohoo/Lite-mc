package com.litemc.android;

/** Safe default: never claim Java Edition has started until a real runtime bridge is bundled. */
final class RuntimeUnavailableEngine implements LaunchEngine {
    @Override public Result launch(LaunchRequest request) {
        return new Result(false, "Android Java 运行时桥接尚未集成；无法安全地启动 Minecraft。请参阅 android/ARCHITECTURE.md。");
    }
}
