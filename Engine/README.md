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

当前只有说明文件，未指定实际 Gradle 模块/版本、生成工程或引入原版依赖。
