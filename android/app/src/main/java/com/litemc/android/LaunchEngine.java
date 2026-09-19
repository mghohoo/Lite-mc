package com.litemc.android;

/**
 * Boundary between the Android launcher UI/data layer and the Java-on-Android
 * runtime. A production engine must own/download a JRE, native GLFW/LWJGL
 * replacements and GL translation, then start Minecraft in-process.
 */
interface LaunchEngine {
    Result launch(LaunchRequest request);

    final class LaunchRequest {
        final String version;
        final String username;
        final boolean fabric;
        LaunchRequest(String version, String username, boolean fabric) {
            this.version = version; this.username = username; this.fabric = fabric;
        }
    }
    final class Result {
        final boolean started; final String message;
        Result(boolean started, String message) { this.started = started; this.message = message; }
    }
}
