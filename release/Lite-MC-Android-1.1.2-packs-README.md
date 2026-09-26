# Lite-MC Android 1.1.2-dev · 整合包修复

安装文件：`Lite-MC-Android-1.1.2-packs.apk`。使用现有 Lite-MC Development 签名，版本号递增，可覆盖安装同包名的 1.1.1 开发版。不要先卸载旧版，以免清除数据。

SHA-256：`DDBAE58D1756662D13AFA43FF99CF690DADFFAAE46D376D7DBC22E97C685D7EA`。

## 使用

资源 → 选择「整合包」→ Modrinth → 输入项目名或留空搜索热门 → 选版本。

- 「安装为新实例」按包内清单选择 Minecraft 和 Fabric 版本，并安装包内的 Mod 与配置，不改动已有实例。包内的 Fabric API 以清单为准，不再另装最新版。
- 「仅下载整合包文件」下载经官方哈希校验的 `.mrpack` / `.zip`，然后打开 Android 系统保存窗口。取消保存会保留下载缓存。
- Forge、NeoForge、Quilt 整合包可以浏览和下载文件，但本版仍不能自动安装这些加载器。下载归档不等于可以直接启动。
- CurseForge 必须在设置中配置有效 API key；Modrinth 不需要。作者禁止第三方下载的 CurseForge 文件会明确报错，不会跳过必需 Mod 冒充安装完成。
- Modrinth 服务端专用文件不会安装到客户端；可选客户端文件默认包含。

## 修复

- 整合包搜索不再要求先装游戏，也不再继承主页当前版本的过滤条件。
- 独立整合包版本页、Minecraft 版本筛选、中文状态提示、失败重试与回车搜索。
- 精确下载所选发布版本，校验项目归属、文件 ID 与官方哈希；支持中文整合包文件名。
- 移除直接向当前实例解压的旧路径；内容先暂存并校验，完成后才登记实例。
- 限制 ZIP 路径、文件数、展开大小；校验双哈希与文件大小，按顺序应用客户端配置。
- 放行受限官方资源 CDN 的有效图片，让资源图标可加载；其他远程页面仍不能获得原生接口。

## 验证边界

已执行 Java 回归、真实 Chromium 手机/平板布局与交互测试、Android APK 编译及签名检查。官方 Modrinth 搜索、发布版本与整合包归档下载进行了联网检查。

真实联网回归还使用产品网络与整合包代码，完成 Fabulously Optimized 5.4.1（Minecraft 1.20.1 / Fabric 0.14.23）的 51 个客户端文件下载及双哈希校验，连同配置提交为 78 个文件、27,902,856 字节。整个测试仅使用临时目录，结束后清理，没有修改玩家实例。可重复运行 `node android-runtime/tests/mobile-packs-regression.mjs --live-smoke`（显式联网，Mod 下载声明量上限 50 MiB）。

没有连接 Android 真机，未声称已在平板完成整合包进游戏测试。CurseForge 使用确定性测试数据验证，没有使用真实 API key 联网测试。本次不改变 26.x 现有兼容判断。

界面与服务层由 Lite-MC 开发；Android 游戏运行底层仍含 Amethyst/Pojav 及其开源依赖，保留原有署名和许可证。
