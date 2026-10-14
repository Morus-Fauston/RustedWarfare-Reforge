# PC 客户端与打包入口

现行目标为 Windows；平台启动与随包运行时打包共用 core/client。离线本机执行核心，联网消费授权结果；目录名不自动新增其他桌面平台承诺。

当前已建立依赖 client 的空 Kotlin/JVM 模块，字节码目标 Java 17；没有可运行客户端或发行包。从 Engine 目录执行 `./gradlew.bat :pc:build --no-daemon` 只验证模块构建。总入口见[Engine](../README.md)。
