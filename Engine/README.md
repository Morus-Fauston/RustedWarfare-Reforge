# 独立引擎代码与总构建入口

本目录用于实际可运行的独立 Kotlin 引擎。目录建立不等于工程可构建或实施授权。

| 入口 | 用途 |
|---|---|
| [core](core/README.md) | 共享玩法核心与内容逻辑 |
| [client](client/README.md) | 共享客户端表现与 UI |
| [android](android/README.md) | Android 应用与打包 |
| [pc](pc/README.md) | Windows 客户端与打包 |
| [server](server/README.md) | Linux 专用服务端与打包 |

平台宿主共用核心，不复制玩法。离线本地模拟与联网权威职责见[离线核心](../Docs/当前决策/离线核心与首个移动切片.md)。目录规则唯一来源为[三线落位](../Docs/当前决策/三线代码与构建落位.md)，任务见[引擎开发线](../.scratch/开发票据/引擎重写/README.md)。

当前已建立 Gradle Wrapper 9.3.1、Kotlin 2.3.20、AGP 9.1.1 工程基线，包含 core/client/pc/android 四个模块；server 暂仅说明。JDK 25 执行构建，共享 JVM 模块和 PC 生成 Java 17 字节码，Android 采用 Java 17 编译目标。未引入原版运行时。

从本目录执行 `./gradlew.bat :core:test --rerun-tasks --no-daemon` 核验最小核心启动测试，执行 `./gradlew.bat :android:assembleDebug --no-daemon` 验证 Android 包装。共享客户端已有 libGDX 1.13.1 工程状态页，Android 已注册应用列表启动入口并接入后端和原生库；PC 仍无应用入口，双方都没有移动玩法。设备启动和绘制需另行验收，构建不证明可操作沙盒通过。
