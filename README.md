# 多秒表计时器

一个轻量的 Android 多秒表应用，适合同时记录多项活动的用时。项目以 MIT 许可证开源，欢迎使用、修改和再发布。

## 功能

- 最多同时创建 7 个秒表，每个秒表有独立的标识色。
- 每个秒表最多记录 50 圈，可单独开始、暂停、继续、计次、重置、改名和删除。
- 每个秒表支持最多 1000 字的独立笔记。
- 可选择多个已暂停的秒表，一次导出到同一个 Excel 工作簿；表格包含秒表名称、笔记、各圈用时和平均值。
- 已删除的秒表进入回收站，可在 7 天内恢复，也可手动清空；过期内容会自动清理。
- 重置前会再次确认，减少误操作。
- 自动保存秒表状态；应用关闭后，运行中的计时会按真实时间继续计算。
- 可导入整页壁纸，并可分别设置每个秒表卡片的背景色、透明度和边框颜色；卡片操作按钮同步透明。
- 提供主题色、深色模式、紧凑列表和字体大小等外观设置。
- 记录秒表操作日志，可导出最近 30 天的 CSV 文件。
- 使用黑色状态栏和导航栏安全区，并提供简约线框钟表应用图标。

## 构建环境

- Android Studio 或 Android SDK（需要 Android SDK Platform 36）
- JDK 17 或更高版本
- 首次构建需要网络连接，以下载 Gradle 和 Android Gradle Plugin

Android Studio 通常会自动配置 SDK。命令行构建时，请设置 `ANDROID_SDK_ROOT`（或 `ANDROID_HOME`），并让 `JAVA_HOME` 指向有效的 JDK。

## 构建 APK

Windows PowerShell：

```powershell
.\gradlew.bat :app:assembleDebug
```

也可以使用便捷脚本：

```powershell
powershell -ExecutionPolicy Bypass -File .\build-apk.ps1
```

macOS 或 Linux：

```sh
./gradlew :app:assembleDebug
```

构建完成后，APK 位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 下载 APK

可在 [GitHub Releases](https://github.com/modan1998/duo-miao-biao-ji-shi-qi/releases/latest) 下载多秒表计时器 Android APK。调试版也可按上方说明从源码构建。

## 下载 / 安装方式

- **Android**：从 [GitHub Releases](https://github.com/modan1998/duo-miao-biao-ji-shi-qi/releases/latest) 下载 APK 并安装。
- **iPhone / iPad**：用 Safari 打开 [多秒表计时器网页版](https://modan1998.github.io/duo-miao-biao-ji-shi-qi/)，点底部“分享”按钮，选择“添加到主屏幕”，再点“添加”。从主屏幕图标启动后可离线使用。
- **开发者**：Android 可用 Android Studio 打开项目，或运行 `./gradlew :app:assembleDebug`；PWA 源码在 `web/`，无需安装 npm 依赖或执行构建。

首次启用 GitHub Pages 前，请到仓库 **Settings → Pages → Source** 手动选择 **GitHub Actions**。之后合并到 `main` 的提交会触发 `.github/workflows/deploy-pwa.yml` 自动发布。

PWA 网址：<https://modan1998.github.io/duo-miao-biao-ji-shi-qi/>

## iOS 网页版已知限制

- Safari 将页面切到后台时可能暂停 JavaScript，后台不会持续刷新计时界面。秒表按起始时间戳计算，回到页面后会更新为正确用时；不会提供后台常驻或计时通知。
- 网页版不提供 Android 的振动反馈，也不能控制 iOS 状态栏/系统导航栏样式。项目没有蓝牙、NFC 或其他硬件接口功能。
- PWA 数据储存在当前浏览器的 IndexedDB，不与 Android App 或其他设备自动同步；iOS 可能清理长期未使用的网站数据。请定期用“数据 → 导出 JSON 备份”保存，并可用“导入 JSON 备份”恢复。壁纸也包含在 JSON 备份中。
- Android 本地 `SharedPreferences` 数据不能被网页直接读取。两版各自保存数据；Android 现有 Excel/CSV 导出文件不能作为 PWA JSON 备份直接导入。
- 网页版圈速表格导出为 CSV（可用 Excel 等表格软件打开），而不是 Android 版的 XLSX 工作簿。
- iOS 添加到主屏幕后可以离线使用；Safari 普通标签页的存储与独立 App 模式相同，仍可能被系统清理。网页不请求推送权限，也不依赖推送通知。

## PWA 开发与部署

网页版是纯静态 HTML、CSS 和 JavaScript，不连接后端、不调用第三方 API、不包含密钥，也没有新增运行时依赖。秒表、圈速、笔记、设置、回收站和日志使用 IndexedDB 保存；浏览器禁用 IndexedDB 时会尝试 localStorage，壁纸在这种回退模式下不能持久保存。

### 本地预览

在仓库根目录运行：

```sh
python -m http.server 8000 --directory web
```

打开 <http://localhost:8000/>。Service Worker 需要 HTTPS 或 localhost；直接双击 `index.html` 不会启用离线缓存。

### GitHub Pages

`.github/workflows/deploy-pwa.yml` 在 `main` 有新提交或手动运行时发布 `web/`。网站、manifest、图标和 Service Worker 都使用相对路径，manifest 的 `start_url` / `scope` 为 `./`，Service Worker 以当前目录注册，因此在仓库子路径 `/duo-miao-biao-ji-shi-qi/` 下无需写死域名或根路径，也无需额外构建步骤。

### Cloudflare Pages 备用部署

同一 `web/` 静态目录可直接部署到 Cloudflare Pages，无需改代码：导入本 GitHub 仓库，将根目录设为 `web`，框架预设选 None，构建命令留空（如控制台要求命令可填 `exit 0`），输出目录设为 `.`。也可用 Cloudflare Pages 的 Direct Upload 上传 `web/` 内容。Cloudflare Pages 支持静态 HTML 站点；但中国大陆的访问速度与可用性受运营商和网络路由影响，不能保证一定优于 GitHub Pages。

PWA 不需要 `npm install` 或构建命令；GitHub Actions 会直接发布静态目录。Android 端仍按原 Gradle Wrapper 指令独立构建，不受网页目录影响。

## 开源许可

本项目使用 [MIT License](LICENSE)。
