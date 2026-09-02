package com.femininevoicetrainer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.femininevoicetrainer.audio.VoiceEvaluator
import com.femininevoicetrainer.audio.VoiceFeatureExtractor
import com.femininevoicetrainer.audio.VoiceTypeThresholds
import com.femininevoicetrainer.data.Recording
import java.io.File

/**
 * 主界面 Jetpack Compose 实现
 * Main screen UI with recording controls, F0 display, and recording history
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreenContent(
    viewModel: MainViewModel,
    uiState: MainViewModel.UiState
) {
    val selectedTab = remember { mutableStateOf(0) }
    val tabs = listOf("录音", "历史记录")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("女声训练") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, title ->
                    NavigationBarItem(
                        selected = selectedTab.value == index,
                        onClick = { selectedTab.value = index },
                        icon = {
                            Icon(
                                imageVector = when (index) {
                                    0 -> Icons.Default.Mic
                                    else -> Icons.Default.History
                                },
                                contentDescription = title
                            )
                        },
                        label = { Text(title) }
                    )
                }
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues)) {
            when (selectedTab.value) {
                0 -> RecordingScreen(viewModel = viewModel, uiState = uiState)
                1 -> HistoryScreen(viewModel = viewModel, uiState = uiState)
            }
        }
    }
}

/**
 * 录音界面
 */
@Composable
fun RecordingScreen(
    viewModel: MainViewModel,
    uiState: MainViewModel.UiState
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        // F0 Display
        F0DisplayCard(
            f0 = uiState.currentF0,
            probability = uiState.currentProbability,
            isRecording = uiState.isRecording
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Score Display
        ScoreCard(
            score = uiState.currentScore,
            isRecording = uiState.isRecording
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 录音中实时有效性提示(COD-46:电平条 + 有效语音秒数,不让用户录完才发现数据不足)
        if (uiState.isRecording) {
            LiveValidityCard(
                speechLevel = uiState.liveSpeechLevel,
                voicedSeconds = uiState.liveVoicedSeconds,
                durationMs = uiState.recordingDuration
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Recording Control Button (长按录音)
        RecordingButton(
            isRecording = uiState.isRecording,
            recordingDuration = uiState.recordingDuration,
            onStartRecording = { viewModel.startRecording() },
            onStopRecording = { viewModel.stopRecording() }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 回放结束后的操作:重新录制 / 重听
        if (uiState.showReplayActions && uiState.lastResult != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = { viewModel.resetForRerecord() }) {
                    Text("重新录制")
                }
                Button(onClick = { viewModel.relistenRecording() }) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("重听")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 本轮结果:声线标签 + 判别 + 五维分项 + 太监音预警
        uiState.lastResult?.let { result ->
            ResultCard(result)
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Error message
        uiState.errorMessage?.let { error ->
            ErrorCard(error) {
                viewModel.clearError()
            }
        }

        // Info Card
        InfoCard()
    }
}

/**
 * F0 显示卡片
 */
@Composable
fun F0DisplayCard(
    f0: Double,
    probability: Double,
    isRecording: Boolean
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isRecording)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "实时基频 (F0)",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = if (f0 > 0) String.format("%.1f Hz", f0) else "-- Hz",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = if (isRecording)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (isRecording && probability > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = String.format("置信度: %.0f%%", probability * 100),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 评分显示卡片
 */
@Composable
fun ScoreCard(
    score: Double,
    isRecording: Boolean
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "女声化评分",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = String.format("%.1f / 40", score),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.secondary
            )

            Text(
                text = getFeminizationDescription(score),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

/**
 * 录音中实时有效性卡(COD-46,人类硬性要求「别让用户录完才知道数据不足」):
 * - 声音电平条:0.02(最低可用)/0.04(充足)两档刻度线,低于 0.02 变黄提示偏轻
 * - 有效语音:累计有声秒数进度条,目标 3.0s(与评分门限 MIN_VOICED_DURATION_MS 一致)
 * - 时长:已录秒数对照 3.5s 最低标线(MIN_TOTAL_DURATION_MS)
 */
@Composable
fun LiveValidityCard(
    speechLevel: Double,
    voicedSeconds: Double,
    durationMs: Long
) {
    val minLevel = VoiceTypeThresholds.MIN_SPEECH_LEVEL
    val okLevel = LEVEL_BAR_OK
    val levelBarMax = LEVEL_BAR_MAX
    val voicedTargetSec = (VoiceTypeThresholds.MIN_VOICED_DURATION_MS / 1000.0)
    val minDurationSec = (VoiceTypeThresholds.MIN_TOTAL_DURATION_MS / 1000.0)
    val durationSec = durationMs / 1000.0
    val levelWarn = speechLevel < minLevel

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (levelWarn) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "录音有效性",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (levelWarn) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onSecondaryContainer
            )

            Spacer(modifier = Modifier.height(10.dp))

            // 声音电平条(0.02/0.04 刻度线;低于 0.02 变黄)
            Text(
                text = if (levelWarn) "声音偏轻:拿近一点或稍微大声一点"
                else "声音电平合适",
                style = MaterialTheme.typography.bodySmall,
                color = if (levelWarn) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(modifier = Modifier.height(4.dp))
            Box(modifier = Modifier.fillMaxWidth().height(10.dp)) {
                LinearProgressIndicator(
                    progress = (speechLevel / levelBarMax).toFloat().coerceIn(0f, 1f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp)),
                    color = if (levelWarn) LevelWarnColor else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                // 刻度线:0.02(最低)与 0.04(充足)
                listOf(minLevel / levelBarMax, okLevel / levelBarMax).forEach { frac ->
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .fillMaxHeight()
                            .fillMaxWidth(frac.toFloat().coerceIn(0f, 1f))
                    ) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .width(2.dp)
                                .fillMaxHeight(0.8f)
                                .background(MaterialTheme.colorScheme.outline)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 有效语音秒数进度(目标 3.0s)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LinearProgressIndicator(
                    progress = (voicedSeconds / voicedTargetSec).toFloat().coerceIn(0f, 1f),
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (voicedSeconds >= voicedTargetSec) "有效语音已达标"
                    else String.format("有效语音 %.1f / %.1f 秒", voicedSeconds, voicedTargetSec),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (levelWarn) MaterialTheme.colorScheme.onErrorContainer
                    else MaterialTheme.colorScheme.onSecondaryContainer
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // 已录时长对照 3.5s 最低标线
            Text(
                text = if (durationSec >= minDurationSec) String.format("已录 %.1f 秒(时长已足够)", durationSec)
                else String.format("已录 %.1f 秒(至少 %.1f 秒)", durationSec, minDurationSec),
                style = MaterialTheme.typography.bodySmall,
                color = if (levelWarn) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

/** 电平偏轻警示色(黄,低于 0.02 电平条变黄——COD-45 实时提示规格) */
private val LevelWarnColor = Color(0xFFF6A600)

/** 实时电平条满刻度与「充足」刻度(0.02 最低刻度取 VoiceTypeThresholds.MIN_SPEECH_LEVEL) */
private const val LEVEL_BAR_MAX = 0.08
private const val LEVEL_BAR_OK = 0.04

/**
 * 录音按钮(需求①:长按手势——按下开始录音,松开结束)
 */
@Composable
fun RecordingButton(
    isRecording: Boolean,
    recordingDuration: Long,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(
                    if (isRecording) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                )
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            onStartRecording()
                            tryAwaitRelease()
                            onStopRecording()
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                contentDescription = if (isRecording) "松开结束录音" else "按住开始录音",
                modifier = Modifier.size(44.dp),
                tint = Color.White
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Recording duration
        if (isRecording) {
            Text(
                text = formatDuration(recordingDuration),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Recording status
        Text(
            text = if (isRecording) "录音中,松开结束" else "按住麦克风说话,松开结束",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 历史记录界面
 */
@Composable
fun HistoryScreen(
    viewModel: MainViewModel,
    uiState: MainViewModel.UiState
) {
    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // Statistics header
        StatisticsHeader(
            viewModel = viewModel,
            modifier = Modifier.padding(16.dp)
        )

        // Recordings list
        if (uiState.recordings.isEmpty()) {
            EmptyHistoryMessage()
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(uiState.recordings) { recording ->
                    RecordingItem(
                        recording = recording,
                        isPlaying = uiState.isPlaying,
                        onPlay = { viewModel.playRecording(recording) },
                        onStop = { viewModel.stopPlayback() },
                        onDelete = { viewModel.deleteRecording(recording) }
                    )
                }
            }
        }

        // Error message
        uiState.errorMessage?.let { error ->
            ErrorCard(error) {
                viewModel.clearError()
            }
        }
    }
}

/**
 * 统计信息头部
 */
@Composable
fun StatisticsHeader(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val stats = viewModel.getStatistics()

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "训练统计",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatItem(
                    label = "录音总数",
                    value = stats.totalRecordings.toString(),
                    modifier = Modifier.weight(1f)
                )
                StatItem(
                    label = "平均评分",
                    value = String.format("%.1f", stats.averageScore),
                    modifier = Modifier.weight(1f)
                )
                StatItem(
                    label = "最高评分",
                    value = String.format("%.1f", stats.highestScore),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/**
 * 统计项
 */
@Composable
fun StatItem(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onTertiaryContainer
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer
        )
    }
}

/**
 * 录音项
 */
@Composable
fun RecordingItem(
    recording: Recording,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Date and duration
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = recording.getFormattedDate(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = recording.getFormattedDuration(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // F0 / 评分 / 声线
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "基频",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = recording.getFormattedF0(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Column {
                    Text(
                        text = "评分",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = when {
                            recording.hasVoiceProfile() -> recording.getFormattedTotalScore()
                            recording.isDataInsufficient() -> "数据不足"
                            else -> recording.getFormattedScore()
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (recording.isDataInsufficient()) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.secondary
                    )
                }

                Column {
                    Text(
                        text = "声线",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    when {
                        recording.hasVoiceProfile() -> {
                            // 按声线类型着色
                            Text(
                                text = recording.voiceType.orEmpty(),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = voiceTypeColor(recording.voiceType.orEmpty())
                            )
                        }
                        recording.isDataInsufficient() -> Text(
                            text = "数据不足",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        else -> Text(
                            text = recording.getFeminizationDescription(),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(onClick = if (isPlaying) onStop else onPlay) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "停止播放" else "播放录音",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "删除录音",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

/**
 * 空历史记录消息
 */
@Composable
fun EmptyHistoryMessage() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.History,
                contentDescription = "暂无录音记录",
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "暂无录音记录",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 错误卡片
 */
@Composable
fun ErrorCard(
    error: String,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = "错误",
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = error,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "关闭",
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

/**
 * 信息卡片
 */
@Composable
fun InfoCard() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "使用提示",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "• 按住麦克风说话,松开结束录音\n" +
                        "• 录音结束自动回放,可重听或重录\n" +
                        "• 女声典型基频: 165-255 Hz\n" +
                        "• 总分 = 音高35 + 共鸣35 + 稳定性10 + 音质12 + 平滑度8",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 本轮结果卡:声线标签、判别四态、五维分项、太监音预警
 */
@Composable
fun ResultCard(result: MainViewModel.SessionResult) {
    val evaluation = result.evaluation

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "本轮结果",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (evaluation.condition == VoiceEvaluator.VoiceCondition.DATA_INSUFFICIENT) {
                // 数据不足空态:不展示 0-100 分与全零五维条,避免误导;
                // 但必须给出具体原因与改进建议(COD-46 人类硬性要求:不能只说「数据不足」)
                val fail = result.features.failReason
                Text(
                    text = "数据不足",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = when (fail) {
                                    VoiceFeatureExtractor.SessionFailReason.TOO_NOISY -> Icons.Default.Warning
                                    else -> Icons.Default.Info
                                },
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = fail?.label ?: "未能评估",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = fail?.advice
                                ?: "没有捕捉到足够的人声,请长按多说几秒再试一次",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            textAlign = TextAlign.Center
                        )

                        // 有效人声不足且活跃段占比极低:疑似在放音乐/电视
                        fail?.extraAdvice(result.features.activeVoicedRatio)?.let { extra ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = extra,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                textAlign = TextAlign.Center
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        // 量化诊断行:让用户看到差在哪(有效语音秒数/电平)
                        val voicedSec =
                            result.features.voicedFrameCount * VoiceFeatureExtractor.FRAME_MS / 1000.0
                        Text(
                            text = String.format(
                                "已录 %.1f 秒 · 有效语音 %.1f 秒(需 ≥%.0f)· 电平 %.3f(需 ≥%.2f)",
                                result.durationMs / 1000.0,
                                voicedSec,
                                VoiceTypeThresholds.MIN_VOICED_DURATION_MS / 1000.0,
                                result.features.speechLevel,
                                VoiceTypeThresholds.MIN_SPEECH_LEVEL
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                // 总分 + 声线标签 + 判别
                Text(
                    text = String.format("%.1f / 100", evaluation.totalScore),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = voiceTypeColor(evaluation.voiceType?.label.orEmpty()).copy(alpha = 0.18f)
                    ) {
                        Text(
                            text = evaluation.voiceType?.label.orEmpty(),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = voiceTypeColor(evaluation.voiceType?.label.orEmpty())
                        )
                    }
                    Text(
                        text = evaluation.condition.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 太监音预警卡(mismatch > 0.35)
                if (evaluation.condition == VoiceEvaluator.VoiceCondition.EUNUCH_RISK) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "预警",
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "音高已到位,共鸣还需跟上",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // 五维分项(音高35/共鸣35/稳定性10/音质12/平滑度8)
                DimensionBar("音高", evaluation.subScores.pitch, 35.0)
                DimensionBar("共鸣", evaluation.subScores.resonance, 35.0)
                DimensionBar("稳定性", evaluation.subScores.stability, 10.0)
                DimensionBar("音质", evaluation.subScores.quality, 12.0)
                DimensionBar("平滑度", evaluation.subScores.smoothness, 8.0)
            }
        }
    }
}

/**
 * 五维单项进度行
 */
@Composable
private fun DimensionBar(label: String, points: Double, maxPoints: Double) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(52.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        LinearProgressIndicator(
            progress = (points / maxPoints).toFloat().coerceIn(0f, 1f),
            modifier = Modifier
                .weight(1f)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
        )
        Text(
            text = String.format("%.1f/%.0f", points, maxPoints),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(56.dp),
            textAlign = TextAlign.End,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 历史列表按声线类型着色(让用户看到声线迁移轨迹)
 * @param voiceTypeLabel 声线中文标签(Recording.voiceType)
 */
fun voiceTypeColor(voiceTypeLabel: String): Color = when (voiceTypeLabel) {
    "气泡音" -> Color(0xFF8D6E63) // 棕
    "普通男声" -> Color(0xFF42A5F5) // 蓝
    "普通女声" -> Color(0xFFF06292) // 粉
    "萝莉音" -> Color(0xFFE91E63) // 品红
    "御姐音" -> Color(0xFF7E57C2) // 紫
    else -> Color.Gray
}

/**
 * 格式化时长
 */
private fun formatDuration(duration: Long): String {
    val seconds = (duration / 1000).toInt()
    val minutes = seconds / 60
    val remainingSeconds = seconds % 60
    return String.format("%02d:%02d", minutes, remainingSeconds)
}

/**
 * 获取女声化评价
 */
private fun getFeminizationDescription(score: Double): String {
    return when {
        score >= 38.0 -> "高度女声化"
        score >= 30.0 -> "中高女声化"
        score >= 20.0 -> "中等女声化"
        score >= 10.0 -> "低度女声化"
        else -> "极低女声化"
    }
}
