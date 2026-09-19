# Lite-MC

一个支持原版、Fabric、离线模式和 Microsoft 正版帐号的轻量 Minecraft 启动器。

## 启动

```powershell
npm install
npm start
```

也可以直接运行 `release/Lite-MC-1.1.0-Portable-x64.exe`，无需安装 Node.js；需要开始菜单与桌面快捷方式时，使用 `release/Lite-MC-1.1.0-Setup-x64.exe`。

首次启动游戏时，启动器会从 Mojang 官方资源服务器下载版本清单、游戏本体、资源和依赖库；请保持网络连接。

推荐流程：选择游戏版本与 Vanilla/Fabric → 点击“下载” → 等待状态显示“已安装” → 点击“启动 Minecraft”。使用 Mod 时请选择 Fabric。

## 已实现

- 从 Mojang 官方版本清单选择最新或历史原版正式版
- 安装并启动 Fabric，加载器元数据和依赖来自 Fabric 官方服务
- 在启动器内搜索并安装 Modrinth 或 CurseForge Mod，并按游戏版本与 Fabric 兼容性过滤
- 自动安装 Mod 声明的必需依赖，并为每个 Fabric 游戏版本使用独立实例目录，避免跨版本 Mod 冲突
- 在启动前单独下载并安装版本，显示安装状态和下载进度
- 已安装版本缓存依赖列表，启动时跳过重复的全量资源哈希扫描
- 离线模式自定义 3–16 位游戏名
- Microsoft 正版帐号登录，并以正版身份启动游戏
- 主页显示正版帐号的当前皮肤，提供 Steve（Classic）/Alex（Slim）模型切换和 PNG 皮肤上传
- 设置最大内存与 Java 可执行文件
- 根据版本元数据自动下载 Mojang 官方 Java 运行时（例如 26.3 使用 Java 25）
- 启动器内选择简体中文、繁体中文、英语或日语，启动前写入游戏设置以避免菜单内热切换崩溃
- 自动下载、安装并启动原版 Minecraft；下载采用 Mojang 官方资源域名和 16 路资源并发
- 显示下载/启动日志，并可直接打开独立游戏目录

## 正版帐号与皮肤

点击“微软正版”后，Lite-MC 会打开 Microsoft 的官方登录窗口；不会收集或保存你的密码。用于保持登录的刷新凭据通过 Electron/Windows 安全存储加密保存，可主动退出并清除。

上传皮肤只支持 PNG，并会提交到 Minecraft 官方服务。模型选择会以当前皮肤 URL 重新提交 Classic（Steve）或 Slim（Alex）变体；请确保皮肤尺寸与所选模型相符。

## 离线皮肤限制

Minecraft 的离线账户没有向 Mojang 验证身份，因此原版客户端只会使用默认 Steve/Alex 皮肤。若要在离线服务器中显示自定义皮肤，服务器必须自行安装并配置皮肤插件/模组；这并不是启动器单方面能可靠实现的功能。

## CurseForge API Key

Modrinth 搜索无需配置。CurseForge 官方 API 要求 API Key：在 Mod 下载区选择 CurseForge，填写 Key 并点击“安全保存 Key”。Lite-MC 使用系统安全存储加密该 Key，不会把它写入普通设置文件。

## 数据位置

游戏文件和配置写入 Electron 的用户数据目录下的 `minecraft` 文件夹，不会写入本项目目录。Fabric 的 Mod 与存档位于 `minecraft/instances/fabric-<版本>`，不同版本互不混用。界面上的“打开游戏目录”和“打开 Mods 文件夹”按钮可直达对应位置。
