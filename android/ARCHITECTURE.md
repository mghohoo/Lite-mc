# Lite-MC Android 架构（0.1 起步）

`android/` 是独立的原生 APK，不调用或打包 Electron/Node；桌面版保持不变。

## 可落地的分层

1. **Android UI（Kotlin/Compose 或当前 Java 原型）**：帐号、实例、下载队列、日志和启动页。
2. **Launcher domain**：Mojang version/asset 下载、Fabric 元数据、Modrinth/CurseForge 客户端、实例目录和 Mod 解析。可复用桌面端的 HTTP 规则，但需要以 Kotlin 重写，不能使用 `minecraft-launcher-core`。
3. **身份与皮肤**：Microsoft OAuth 2.0 Authorization Code + PKCE，Android Keystore 加密刷新令牌；Xbox Live → XSTS → Minecraft Services，并通过官方 Minecraft profile API 管理皮肤。离线帐号仅保留名称；原版离线皮肤仍不可能由启动器强行同步。
4. **运行时 bridge**：下载/验证 Mojang Java runtime，提供 Android 上的 JVM、AWT/GLFW/LWJGL 替代层和 OpenGL ES/Vulkan translation，随后在应用进程中启动 Java Edition。

## 实际启动的硬性前置条件

标准 Mojang Java 运行时和 `minecraft-launcher-core` 都不能在 Android 上直接运行。完整实现必须集成一个维护中的 Java-on-Android 运行时与渲染桥（JRE + native window/input + GL4ES/ANGLE/Vulkan translation + patched LWJGL）。最可行的路线是以 **PojavLauncher 系生态**作为独立、明确许可的 runtime 子模块/分发依赖，再以 GPL-3.0 兼容方式发布整个 APK；不得把其 native 库或代码当作未声明的闭源依赖。

这也需要：

- 一个 Lite-MC 自己注册的 Microsoft public-client ID 和 Android redirect URI；不能复用桌面端或第三方启动器的 client ID。
- runtime 的 ABI 构建产物（至少 arm64-v8a；可选 armeabi-v7a/x86_64）与测试真机。
- CurseForge 分发授权/API key。Modrinth 可直接使用公开 API。

当前提交提供了可构建的原生壳、官方正式版清单读取、离线名称验证，以及 `LaunchEngine` 边界。它故意在缺少运行时 bridge 时阻止“启动”，不会虚报 Minecraft 已运行。

## 下一步提交顺序

1. 确认 runtime 来源、许可证和要支持的 ABI/API 范围；接入 bridge，并用一个已拥有的 Java Edition 版本在 arm64 真机启动。
2. 添加 Storage Access Framework/应用专属目录的实例仓库，并移植官方版本/资源下载和校验。
3. 接入 Fabric、Modrinth、CurseForge；所有下载做 SHA-1/大小校验和取消/恢复。
4. 注册 Microsoft OAuth client，接入 PKCE 与 Keystore token storage，最后加官方皮肤上传。
