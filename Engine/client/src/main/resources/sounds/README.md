# 默认自有音频（30 核心事件占位音效）

本目录包含 30 份随包分发的自有 16-bit PCM 单声道（`22050 Hz`）WAV 占位音效，全部通过确定性数学波形合成生成（保留既有 5 份 `tank-fire`、`naval-main-fire`、`naval-missile-fire`、`nuke-launch`、`nuke-explode` 并于 `UI-P004-006` 补齐其余 25 份武器射击、能量电弧、爆炸崩塌、虫族有机声效、战术指令、UI 交互与战略警报音效），未使用任何原版录音采样，不声明原版听感等价。

- 默认无原版目录运行时：`sounds.json` 与 `PcBaseSoundResources` 将全部 30 个主事件（`reforge:events/*`）及衍生玩法别名绑定至上述 30 份自有 WAV，峰值幅度均严格满足 `0.25 <= peak < 0.95`（无削波、非静音、零直流偏置）。
- 启用经校验的原版 PC 资源包后：`pc-original-resources-profile.json` 将上述事件精确覆盖至原版 `res/raw/*.ogg` / `res/raw/*.wav` 音效源；玩法侧音量、视野裁剪、距离衰减与并发限流仍统一走既有客户端音频接缝。
