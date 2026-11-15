# 开发说明

## 环境

使用 JDK 21，安装 Android SDK Platform 36 和 Build Tools 36.0.0，设置 `ANDROID_HOME` 或通过 Android Studio 生成本地 `local.properties`。后者不进入版本控制。Wrapper 的发行 ZIP 带 SHA-256 校验，首轮构建需网络。

```bash
sdkmanager 'platforms;android-36' 'build-tools;36.0.0'
bash gradlew :app:assembleDebug
bash gradlew :app:verifyMvp :app:testDebugUnitTest :app:lintDebug :app:lintRelease
```

Windows 可使用 `gradlew.bat` 执行同样的 Gradle 任务。发布辅助脚本和 CI 面向 Linux，签名步骤也可在 WSL 中运行。

## 工程结构

| 位置 | 职责 |
| --- | --- |
| `app/src/main/.../core/` | 登录、网络协议、设备与控制状态机 |
| `AndroidRuntime.kt` | 加密存储、网络、后台任务和短时前台执行 |
| `MainActivity.kt`、`ShowerScreen.kt` | 应用页面和交互 |
| `WidgetProvider.kt`、`res/` | 桌面小组件和界面资源 |
| `app/src/test/` | 模拟传输、状态机及 Android 窗口测试 |
| `app/src/debug/`、`app/src/androidTest/` | 隔离的界面预览和设备端视图检查 |

包名保留为 `local.rea.shower`，以维持既有应用标识；更换包名会成为另一个应用，不应作为常规升级步骤。

## 测试与预览

`verifyMvp` 执行核心协议和状态机检查；`testDebugUnitTest` 使用 Robolectric 检查 Android API 26 / 30 / 36 的窗口启动与小组件添加反馈。Robolectric 首次运行需要下载相应系统镜像依赖，测试本身不访问真实业务服务。

```bash
bash gradlew -PuiPreview=true :app:assembleDebug :app:assembleDebugAndroidTest
```

预览构建使用独立包名 `local.rea.shower.preview` 和模拟状态，避免读取真实应用账号。预览 Activity 仅存在于 debug 变体，正式 APK 不包含这些入口。设备端预览未包含在 GitHub 托管 CI 的验证范围内。

默认调试签名由 Android 工具生成。已有本机开发安装需要保留同一签名时，可设置 `SHOWER_DEBUG_KEYSTORE` 为已有调试密钥的绝对路径；它仅影响 debug。密码沿用 Android 标准调试密钥配置。

若已离线缓存 Robolectric 依赖，可以设置 `SHOWER_TEST_MAVEN_REPO` 为自己的 Maven 缓存目录；未设置时采用默认缓存。公开工程不依赖任何上级目录或专用工具安装路径。

## 公开文件检查

克隆后使用 `python3 scripts/check_public_source.py --git`。全新导出且尚未构建的文件夹可用不带 `--git` 的方式检查。脚本验证逐文件清单、阻止符号链接、检查常见凭据和意外本机路径；它不能取代对新增内容及图片的人工审查，也不清理 Git 历史。
