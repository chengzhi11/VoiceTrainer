package com.femininevoicetrainer.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.femininevoicetrainer.ui.theme.FeminineVoiceTrainerTheme

// material3 无 MaterialTheme.elevation,取 M2 medium(8dp)作为对话框表面 tonal elevation 等价常量
private val DialogTonalElevation = 8.dp

// 自动化钩子 extra 名(adb shell am start --ei auto_record_ms <毫秒>)
private const val AUTO_RECORD_MS_EXTRA = "auto_record_ms"

// 运行时仅需麦克风权限:录音写入 filesDir(应用私有目录),不使用共享存储。
// (此前按 API 33+ 请求 WRITE/READ_EXTERNAL_STORAGE 会被系统直接判拒,
// 授权回调 allGranted=false → permissionGranted=false → 主界面空白的历史回归)
private val requiredPermissions = arrayOf(Manifest.permission.RECORD_AUDIO)

/**
 * 主Activity
 * MainActivity for the Feminine Voice Trainer app
 */
class MainActivity : ComponentActivity() {

    // 自动化钩子:--ei auto_record_ms <毫秒>,自动开始并在 N ms 后停止。
    // 用 state 持有,保证 onNewIntent(已在前台时 am start)也能驱动组合层触发
    private val autoRecordMsState = mutableStateOf(-1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        autoRecordMsState.value = intent?.getIntExtra(AUTO_RECORD_MS_EXTRA, -1) ?: -1

        setContent {
            FeminineVoiceTrainerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        checkPermissions = { checkPermissions() },
                        requestPermissions = { requestPermissions() },
                        autoRecordMs = autoRecordMsState.value
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        autoRecordMsState.value = intent.getIntExtra(AUTO_RECORD_MS_EXTRA, -1)
    }

    /**
     * 检查权限
     */
    private fun checkPermissions(): Boolean {
        return requiredPermissions.all { permission ->
            ContextCompat.checkSelfPermission(
                this,
                permission
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * 请求权限
     */
    private fun requestPermissions() {
        // This will be handled by the Compose UI
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}

/**
 * Main Screen Composable
 */
@Composable
fun MainScreen(
    viewModel: MainViewModel = viewModel(),
    checkPermissions: () -> Boolean = { false },
    requestPermissions: () -> Unit = {},
    autoRecordMs: Int = -1
) {
    val uiState by viewModel.uiState.collectAsState()

    // Check permissions on launch
    LaunchedEffect(Unit) {
        val hasPermissions = checkPermissions()
        viewModel.setPermissionGranted(hasPermissions)
        if (!hasPermissions) {
            viewModel.showPermissionDialog()
        }
    }

    // 自动化录音钩子:auto_record_ms > 0 时自动开始并在 N ms 后停止
    LaunchedEffect(autoRecordMs) {
        viewModel.onAutoRecordRequested(autoRecordMs)
    }

    // Permission request launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        // 单次原子更新:permissionGranted 与对话框消失状态保持一致
        viewModel.setPermissionGranted(result.values.all { it })
    }

    // Show permission dialog if needed
    if (uiState.showPermissionDialog) {
        PermissionDialog(
            onRequestPermission = {
                permissionLauncher.launch(requiredPermissions)
                viewModel.hidePermissionDialog()
            },
            onDismiss = {
                viewModel.hidePermissionDialog()
            }
        )
    }

    // Main content
    if (uiState.permissionGranted) {
        VoiceTrainerContent(
            viewModel = viewModel,
            uiState = uiState
        )
    }
}

/**
 * Permission Dialog
 */
@Composable
fun PermissionDialog(
    onRequestPermission: () -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss
    ) {
        androidx.compose.material3.Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = DialogTonalElevation
        ) {
            androidx.compose.foundation.layout.Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
            ) {
                androidx.compose.material3.Text(
                    text = "需要录音权限",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )

                androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(16.dp))

                androidx.compose.material3.Text(
                    text = "本应用需要录音权限来进行语音分析和女声化评分。请在设置中授予权限。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(24.dp))

                androidx.compose.foundation.layout.Row(
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    androidx.compose.material3.TextButton(
                        onClick = onDismiss
                    ) {
                        androidx.compose.material3.Text("取消")
                    }

                    androidx.compose.material3.Button(
                        onClick = onRequestPermission
                    ) {
                        androidx.compose.material3.Text("授予权限")
                    }
                }
            }
        }
    }
}

/**
 * Voice Trainer Content
 */
@Composable
fun VoiceTrainerContent(
    viewModel: MainViewModel,
    uiState: MainViewModel.UiState
) {
    MainScreenContent(
        viewModel = viewModel,
        uiState = uiState
    )
}
