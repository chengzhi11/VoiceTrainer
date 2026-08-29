# VoiceTrainer — 女声训练

一款 Android 女声训练应用：通过实时声学分析帮助使用者进行女声练习，所有音频处理与数据存储均在本地完成，不上传任何录音。

## 功能

- **长按录音**：长按录音按钮开始，松开结束；录音过程中实时显示基频（F0）曲线
- **自动回放**：录音结束后自动播放，方便即时对照试听
- **五维评分**：从音高、共鸣、稳定性、音质、平滑度五个维度加权打分（总分 100），并保留各维度子分
- **声线识别**：基于 F0 与共振峰间距做声线判别，输出自然女声 / 太监音风险 / 男声区 / 过渡区四态结论，并给出声线标签（普通男声 / 普通女声 / 萝莉音 / 御姐音等五类）；有效语音不足时不出具结论，避免误导
- **历史记录**：录音与评分本地留存（Room 数据库），支持回放、删除与进步曲线查看

## 技术栈

- 语言：Kotlin
- UI：Jetpack Compose（Material 3）
- 架构：MVVM + Kotlin Coroutines
- 存储：Room
- 音频：Android AudioRecord 采集，[TarsosDSP](https://github.com/JorenSix/TarsosDSP)（YIN 算法）做基频检测；共振峰间距、HNR、Jitter 等特征为纯 Kotlin 实现
- 构建：Gradle（Kotlin DSL），minSdk 26 / targetSdk 34，JDK 17

## 构建

仓库根目录下的 `app/` 即 Android 工程根。

**Android Studio**：打开仓库中的 `app/` 目录，等待 Gradle 同步完成后直接运行。

**命令行**（需 JDK 17 与 Android SDK 34）：

```bash
cd app
./gradlew assembleDebug      # 构建 debug APK
./gradlew test               # 运行单元测试
```

## 仓库结构

```
app/       # Android 工程（源码、构建配置、单元测试）
scripts/   # 真机回环自测脚本（Mac 发声 → 手机录音 → 量化判据）
assets/    # 纯合成测试音源（440Hz 正弦、200→600Hz 扫频）
```

`scripts/` 下的回环测试脚本依赖 `adb` 连接的真机/模拟器，用于验证「播放测试音 → App 录音 → F0 判据」的完整链路。

## 隐私

- 所有音频数据本地处理，不上传云端
- 不收集任何个人信息，无第三方统计或追踪

## 许可证

本项目以 [GNU GPL v3](LICENSE)（GPL-3.0-only）发布，完整许可证文本见根目录 `LICENSE` 文件。

选择 GPL v3 是因为核心音频分析依赖 [TarsosDSP](https://github.com/JorenSix/TarsosDSP) 采用 GPL v3 许可证，本项目与其许可证兼容。

## 致谢

- [TarsosDSP](https://github.com/JorenSix/TarsosDSP) — 基频检测（YIN）等音频分析能力
- [Accompanist](https://google.github.io/accompanist/) — Compose 权限处理
- Android Jetpack（Compose / Room / Lifecycle / Navigation）
