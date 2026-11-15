# 第三方声明

本应用原创代码采用 MIT。以下依赖和构建组件仍按其原有许可证提供；MIT 不替代第三方许可证。

| 组件 | 版本 | 用途 | 许可证 / 上游 |
| --- | --- | --- | --- |
| Gson | 2.11.0 | JSON 处理，随 APK 分发 | Apache-2.0；[Google Gson](https://github.com/google/gson)；Copyright Google Inc. |
| Kotlin Standard Library | 2.2.21 | Kotlin 运行时，随 APK 分发 | Apache-2.0；[JetBrains Kotlin](https://github.com/JetBrains/kotlin)；Copyright JetBrains s.r.o. and Kotlin Programming Language contributors |
| JetBrains Java Annotations | 13.0 | Kotlin 运行时的传递依赖 | Apache-2.0；[JetBrains Java Annotations](https://github.com/JetBrains/java-annotations)；Copyright JetBrains s.r.o. |
| Gradle Wrapper | 8.13 | 仓库中的构建启动器 | Apache-2.0；[Gradle](https://github.com/gradle/gradle)；Copyright Gradle contributors |

[Apache License 2.0 全文](licenses/Apache-2.0.txt) 随仓库和 APK 一起提供。本声明、MIT 及第三方许可保存在 APK 的 `assets/legal/` 目录。

仅开发和测试时使用：JUnit 4.13.2（EPL-1.0）、Robolectric 4.17（MIT）、Android Gradle Plugin 8.13.2 和 Kotlin Gradle Plugin 2.2.21（Apache-2.0）。工具及其传递依赖由构建系统下载，测试依赖不打包进正式 APK。Android SDK 由开发者按其条款单独安装。

wmxy、智慧用水及相关服务名称仅用于说明兼容性，归各自权利人所有；本项目不提供这些产品的代码或安装包，不代表其官方授权或背书。
