# 多秒表计时器

一个轻量的 Android 多秒表应用，适合同时记录多项活动的用时。项目以 MIT 许可证开源，欢迎使用、修改和再发布。

## 功能

- 最多同时创建 7 个秒表，每个秒表都有独立颜色。
- 每个秒表最多记录 50 圈，可单独开始、暂停、继续、计次、重置、改名和删除。
- 每个秒表支持最多 1000 字的独立笔记。
- 可选择多个已暂停的秒表，一次导出到同一个 Excel 工作簿；表格包含秒表名称、笔记、各圈用时和平均值。
- 已删除的秒表进入回收站，可在 7 天内恢复，也可手动清空；过期内容会自动清理。
- 重置前会再次确认，减少误操作。
- 自动保存秒表状态；应用关闭后，运行中的计时会按真实时间继续计算。
- 提供主题色、深色模式、紧凑列表和字体大小等界面设置。
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

将 APK 传到 Android 设备后即可安装。调试版 APK 仅用于本地安装和验证。

## 开源许可

本项目使用 [MIT License](LICENSE)。
