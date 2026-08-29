# FeminineVoiceTrainer - 女声训练 App

A professional voice training application for Android that helps users develop feminine voice characteristics through real-time audio analysis and scoring.

## 功能特性 (Features)

### 核心功能 (Core Features)
- **实时录音** (Real-time Recording): Low-latency audio recording with real-time feedback
- **基频分析** (F0 Analysis): Real-time fundamental frequency (F0) detection using TarsosDSP YIN algorithm
- **女声化打分** (Feminization Scoring): 0-40 point scoring system based on voice pitch analysis
- **历史记录** (Recording History): Local database storage with playback and management
- **权限管理** (Permission Management): User-friendly permission request system

### 技术亮点 (Technical Highlights)
- **实时性能** (Real-time Performance): <100ms audio latency, <50ms F0 detection
- **隐私优先** (Privacy-First): All data processed locally, no cloud uploads
- **现代架构** (Modern Architecture): Jetpack Compose + MVVM + Kotlin Coroutines
- **专业算法** (Professional Algorithm): Industry-standard YIN pitch detection
- **Material Design** (Material 3): Beautiful, responsive UI with Chinese localization

## 技术栈 (Tech Stack)

### 开发环境 (Development Environment)
- **Language**: Kotlin 1.9.20
- **Build System**: Gradle 8.2 with Kotlin DSL
- **IDE**: Android Studio Hedgehog (2023.1.1) or higher
- **JDK**: OpenJDK 17
- **Target SDK**: Android 14 (API 34)
- **Min SDK**: Android 8.0 (API 26)

### 核心依赖 (Core Dependencies)
- **UI Framework**: Jetpack Compose BOM 2023.10.01
- **Architecture**: MVVM with ViewModel
- **Database**: Room 2.6.1
- **Audio Processing**: TarsosDSP (YIN algorithm)
- **Async**: Kotlin Coroutines 1.7.3
- **Testing**: JUnit 4, Mockito, Kotlin Coroutines Test

## 项目结构 (Project Structure)

```
app/
├── build.gradle.kts                 # Module build configuration
├── proguard-rules.pro              # ProGuard rules
└── src/
    ├── main/
    │   ├── java/com/femininevoicetrainer/
    │   │   ├── data/               # Data layer
    │   │   │   ├── Recording.kt    # Recording entity
    │   │   │   ├── RecordingDao.kt # Database access
    │   │   │   └── AppDatabase.kt  # Room database
    │   │   ├── audio/              # Audio processing layer
    │   │   │   ├── AudioRecorder.kt   # Recording/playback manager
    │   │   │   ├── PitchAnalyzer.kt   # F0 analysis with YIN
    │   │   │   └── ScoringAlgorithm.kt # Feminization scoring
    │   │   └── ui/                 # UI layer
    │   │       ├── MainActivity.kt     # Main activity
    │   │       ├── MainViewModel.kt    # ViewModel
    │   │       ├── MainScreen.kt       # Compose UI
    │   │       └── theme/              # Material 3 theme
    │   └── res/                       # Android resources
    │       ├── values/                # Strings, themes
    │       └── xml/                   # Backup rules, permissions
    └── test/                         # Unit tests
        └── java/com/femininevoicetrainer/
            ├── audio/                 # Audio layer tests
            └── data/                 # Data layer tests
```

## 安装与构建 (Installation & Build)

### 环境要求 (Prerequisites)
- Android Studio Hedgehog or higher
- JDK 17
- Android SDK 34
- An Android device or emulator running API 26+

### 构建步骤 (Build Steps)

1. **克隆项目** (Clone the repository):
   ```bash
   git clone <repository-url>
   cd app/
   ```

2. **打开项目** (Open project):
   - Open Android Studio
   - Select "Open an Existing Project"
   - Navigate to the project directory

3. **同步Gradle** (Sync Gradle):
   - Android Studio will automatically prompt to sync Gradle
   - Click "Sync Now" or use `File → Sync Project with Gradle Files`

4. **构建调试版本** (Build debug version):
   ```bash
   ./gradlew assembleDebug
   ```

5. **构建发布版本** (Build release version):
   ```bash
   ./gradlew bundleRelease
   ```

6. **运行测试** (Run tests):
   ```bash
   # Unit tests
   ./gradlew test

   # Instrumented tests
   ./gradlew connectedAndroidTest
   ```

7. **安装到设备** (Install to device):
   ```bash
   ./gradlew installDebug
   ```

## 使用说明 (Usage Guide)

### 首次使用 (First Time Use)
1. 启动应用后，会请求录音权限
2. 授予录音权限以使用核心功能
3. Android 10及以下设备还需授予存储权限

### 录音流程 (Recording Process)
1. 点击麦克风图标开始录音
2. 实时查看基频(F0)和女声化评分
3. 再次点击麦克风图标停止录音
4. 录音自动保存并计算最终评分

### 历史记录 (Recording History)
1. 切换到"历史记录"标签
2. 查看所有录音记录
3. 点击播放图标回放录音
4. 点击删除图标移除录音

## 打分算法 (Scoring Algorithm)

### 基频范围 (F0 Range)
- **女声典型范围**: 165-255 Hz (平均约 220 Hz)
- **男声典型范围**: 85-180 Hz (平均约 120 Hz)

### 评分公式 (Scoring Formula)
```
IF F0 >= 205 Hz THEN score = 40 (完全女声化)
ELSE IF F0 >= 180 Hz THEN score = 35 + (F0-180)/25 * 5
ELSE IF F0 >= 150 Hz THEN score = 20 + (F0-150)/30 * 15
ELSE IF F0 >= 100 Hz THEN score = (F0-100)/50 * 20
ELSE score = 0 (典型男声)
```

### 评价等级 (Rating Levels)
- **高度女声化**: 38-40分
- **中高女声化**: 30-37分
- **中等女声化**: 20-29分
- **低度女声化**: 10-19分
- **极低女声化**: 0-9分

## 性能指标 (Performance Metrics)

### 实时性能 (Real-time Performance)
- **音频延迟**: <100ms (录音到显示)
- **F0检测延迟**: <50ms
- **UI帧率**: 60fps
- **内存占用**: <100MB

### 音频质量 (Audio Quality)
- **采样率**: 44.1kHz (CD质量)
- **位深度**: 16-bit PCM
- **声道**: 单声道
- **格式**: WAV

## 测试覆盖 (Test Coverage)

### 单元测试 (Unit Tests)
- **ScoringAlgorithmTest.kt**: 20+ test cases for scoring algorithm
- **PitchAnalyzerTest.kt**: 10+ test cases for pitch analyzer
- **RecordingTest.kt**: 15+ test cases for recording entity

### 测试内容 (Test Coverage)
- ✅ 边界值测试 (Boundary value testing)
- ✅ 线性插值验证 (Linear interpolation validation)
- ✅ 异常情况处理 (Error handling)
- ✅ 格式化功能测试 (Formatting functions)
- ✅ 数据持久化 (Data persistence)

## 文档与验证 (Documentation & Verification)

### 项目文档 (Project Documentation)
- **README.md**: 项目说明与构建指南
- **verify_project.sh**: 项目完整性验证脚本

### 验证项目 (Verify Project)
运行验证脚本检查项目完整性：
```bash
./verify_project.sh
```

## 隐私与安全 (Privacy & Security)

### 数据隐私 (Data Privacy)
- ✅ 所有音频数据本地处理
- ✅ 不上传任何录音到云端
- ✅ 不收集用户个人信息
- ✅ 无第三方追踪或分析

### 权限说明 (Permissions)
- **RECORD_AUDIO**: 录音功能必需
- **WRITE_EXTERNAL_STORAGE**: 仅Android 10及以下需要 (保存录音文件)
- **READ_EXTERNAL_STORAGE**: 仅Android 10及以下需要 (播放录音文件)

## 兼容性 (Compatibility)

### 支持的Android版本 (Supported Android Versions)
- **最低版本**: Android 8.0 (API 26)
- **目标版本**: Android 14 (API 34)
- **测试覆盖**: Android 8.0, 10, 12, 14

### 设备要求 (Device Requirements)
- **麦克风**: 必需
- **存储空间**: 至少100MB可用空间
- **内存**: 建议2GB以上RAM
- **处理器**: ARMv7或更高

## 已知问题 (Known Issues)

### 当前限制 (Current Limitations)
1. Android 11以下设备需要额外存储权限
2. 某些OEM设备可能有录音兼容性问题
3. 删除录音后需手动刷新列表

### 后续改进 (Future Improvements)
1. **Formant分析**: 实现共振峰提取，提供更全面的声学分析
2. **声线分类**: 基于F0和Formant的自动声线分类
3. **音频可视化**: 添加波形图和频谱图显示
4. **练习模式**: 提供目标音高训练和进度追踪
5. **导出功能**: 支持WAV/MP3格式导出录音
6. **多语言**: 添加英文界面支持
7. **性能优化**: 针对低端设备的性能优化
8. **高级指标**: 添加spectral tilt、HNR等声学特征

## 许可证 (License)

This project is developed as part of a voice training application research initiative.

## 联系方式 (Contact)

For questions or feedback about this project, please refer to the main project repository or contact the development team.

---

**Version**: 1.0.0-beta
**Last Updated**: 2026-08-26
**Build Number**: 1

🎤 **Happy Voice Training!** 🚀
