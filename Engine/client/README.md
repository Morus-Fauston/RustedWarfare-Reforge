# 共享客户端表现入口

Android/Windows 共用的 libGDX 表现、输入组织与 Scene2D.ui 位置；客户端表现不代替玩法裁决，联网只消费授权结果。

当前 Kotlin/JVM 模块依赖 core 与固定 libGDX 1.13.1，字节码目标 Java 17。BootstrapScreen 通过 CoreRuntime.start 启动现有工程核心，使用 FitViewport 绘制明确标注尚无移动沙盒的工程状态页，并释放字体/绘制资源。未实现地图、输入、移动或联网；Windows 尚无启动入口。主要规则见[技术栈](../../Docs/ADR/ADR-005-技术栈与工程边界.md)，总入口见[Engine](../README.md)。
