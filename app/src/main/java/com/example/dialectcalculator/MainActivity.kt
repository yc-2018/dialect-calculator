package com.example.dialectcalculator

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private val viewModel by viewModels<DialectCalculatorViewModel> {
        DialectCalculatorViewModel.Factory(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DialectCalculatorTheme {
                val context = LocalContext.current
                val state by viewModel.uiState.collectAsState()
                var showSettings by remember { mutableStateOf(false) }
                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission(),
                ) { granted ->
                    viewModel.updatePermission(granted)
                }

                LaunchedEffect(Unit) {
                    val granted = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO,
                    ) == PackageManager.PERMISSION_GRANTED
                    viewModel.updatePermission(granted)
                }

                if (showSettings) {
                    SettingsDialog(
                        threshold = state.currentThreshold,
                        playbackSpeed = state.playbackSpeed,
                        onDismiss = { showSettings = false },
                        onSaveThreshold = viewModel::updateThreshold,
                        onSavePlaybackSpeed = viewModel::updatePlaybackSpeed,
                        onReset = {
                            showSettings = false
                            viewModel.resetTraining()
                        },
                    )
                }

                DialectCalculatorApp(
                    state = state,
                    onRequestPermission = {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    onStartTraining = viewModel::startTrainingRecording,
                    onStopTraining = viewModel::stopTrainingRecording,
                    onRerecordStart = viewModel::rerecord,
                    onRerecordStop = viewModel::finishRerecord,
                    onPreviewSample = { sample -> viewModel.playTrainingSample(context, sample) },
                    onStartRecognition = viewModel::startRecognitionRecording,
                    onStopRecognition = { viewModel.stopRecognitionRecording(context) },
                    onOpenSettings = { showSettings = true },
                    onDismissError = viewModel::clearError,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DialectCalculatorApp(
    state: AppUiState,
    onRequestPermission: () -> Unit,
    onStartTraining: () -> Unit,
    onStopTraining: () -> Unit,
    onRerecordStart: (TokenType, Int) -> Unit,
    onRerecordStop: (TokenType, Int) -> Unit,
    onPreviewSample: (TrainingSample?) -> Unit,
    onStartRecognition: () -> Unit,
    onStopRecognition: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismissError: () -> Unit,
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("方言语音计算机") },
                actions = {
                    TextButton(onClick = onOpenSettings) {
                        Text("设置")
                    }
                },
            )
        },
    ) { padding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xFFF2FBF5), Color(0xFFE8F2FF)),
                    ),
                ),
        ) {
            when {
                state.isLoading -> LoadingScreen(padding)
                !state.hasRecordPermission -> PermissionScreen(padding, onRequestPermission)
                !state.isTrainingComplete -> TrainingScreen(
                    padding = padding,
                    state = state,
                    onStartTraining = onStartTraining,
                    onStopTraining = onStopTraining,
                    onRerecordStart = onRerecordStart,
                    onRerecordStop = onRerecordStop,
                    onPreviewSample = onPreviewSample,
                )

                else -> MainScreen(
                    padding = padding,
                    state = state,
                    onStartRecognition = onStartRecognition,
                    onStopRecognition = onStopRecognition,
                )
            }
        }
    }

    if (state.errorMessage != null) {
        AlertDialog(
            onDismissRequest = onDismissError,
            confirmButton = {
                TextButton(onClick = onDismissError) {
                    Text("知道了")
                }
            },
            title = { Text("提示") },
            text = { Text(state.errorMessage) },
        )
    }
}

@Composable
private fun LoadingScreen(padding: PaddingValues) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentAlignment = Alignment.Center,
    ) {
        Text("正在加载本地语音资料...", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun PermissionScreen(padding: PaddingValues, onRequestPermission: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "需要麦克风权限才能录入和识别方言语音",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(20.dp))
        Button(onClick = onRequestPermission) {
            Text("授权录音")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrainingScreen(
    padding: PaddingValues,
    state: AppUiState,
    onStartTraining: () -> Unit,
    onStopTraining: () -> Unit,
    onRerecordStart: (TokenType, Int) -> Unit,
    onRerecordStop: (TokenType, Int) -> Unit,
    onPreviewSample: (TrainingSample?) -> Unit,
) {
    val current = state.currentTrainingToken
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "第一步：学习固定词表",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "每个词录 1 遍，建议逐词说清楚，并在词与词之间保留轻微停顿。",
            style = MaterialTheme.typography.bodyLarge,
            color = Color(0xFF415A77),
        )
        LinearProgressIndicator(
            progress = state.completedCount / state.totalCount.toFloat(),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "已完成 ${state.completedCount} / ${state.totalCount}",
            style = MaterialTheme.typography.labelLarge,
        )

        if (current != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("当前词语", style = MaterialTheme.typography.labelLarge)
                    Text(
                        text = current.token.displayText,
                        fontSize = 42.sp,
                        fontWeight = FontWeight.ExtraBold,
                    )
                    Text(
                        text = "当前进度：${current.completedAttempts + 1} / ${TrainingRepository.TRAINING_ATTEMPTS}",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    HoldButton(
                        text = if (state.isRecording && state.activeMode == RecordingMode.Training) {
                            "松开结束当前录音"
                        } else {
                            "按住录入「${current.token.displayText}」"
                        },
                        isActive = state.isRecording && state.activeMode == RecordingMode.Training,
                        onPressStart = onStartTraining,
                        onPressEnd = { onStopTraining() },
                    )
                }
            }
        }

        Text(
            text = "全部词条",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            state.trainingItems.forEach { item ->
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (item.isComplete) Color(0xFFD8F4E7) else Color.White,
                    ),
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(item.token.displayText, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        item.attempts.forEach { attempt ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "第 ${attempt.attemptIndex + 1} 遍",
                                    modifier = Modifier.width(58.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                if (attempt.sample == null) {
                                    Text("待录入", color = Color(0xFFB54708))
                                } else {
                                    OutlinedButton(
                                        onClick = { onPreviewSample(attempt.sample) },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                                    ) {
                                        Text("试听")
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    HoldButton(
                                        text = if (state.isRecording && state.activeMode == RecordingMode.Training) {
                                            "松开"
                                        } else {
                                            "重录"
                                        },
                                        compact = true,
                                        isActive = state.isRecording && state.activeMode == RecordingMode.Training,
                                        onPressStart = { onRerecordStart(item.token, attempt.attemptIndex) },
                                        onPressEnd = { onRerecordStop(item.token, attempt.attemptIndex) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MainScreen(
    padding: PaddingValues,
    state: AppUiState,
    onStartRecognition: () -> Unit,
    onStopRecognition: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
        ) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "识别结果",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = state.lastResultText,
                    style = MaterialTheme.typography.headlineSmall,
                    lineHeight = 34.sp,
                )
                if (state.lastRecognitionConfidence != null) {
                    Text(
                        text = "识别置信度：${"%.0f".format(state.lastRecognitionConfidence * 100)}%",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color(0xFF415A77),
                    )
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0D6E5D)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = "按住说完整式子，松开后开始识别",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                )
                HoldButton(
                    text = if (state.isRecording && state.activeMode == RecordingMode.Recognition) {
                        "松开开始识别"
                    } else {
                        "按住录音"
                    },
                    isActive = state.isRecording && state.activeMode == RecordingMode.Recognition,
                    fillParent = false,
                    buttonColor = Color.White,
                    textColor = Color(0xFF0D6E5D),
                    onPressStart = onStartRecognition,
                    onPressEnd = { onStopRecognition() },
                )
                Text(
                    text = "示例：壹加壹点壹",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFD7F7EF),
                )
            }
        }
    }
}

@Composable
private fun HoldButton(
    text: String,
    isActive: Boolean,
    onPressStart: () -> Unit,
    onPressEnd: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    fillParent: Boolean = true,
    buttonColor: Color = Color(0xFF0D6E5D),
    textColor: Color = Color.White,
) {
    Box(
        modifier = modifier
            .then(if (fillParent) Modifier.fillMaxWidth() else Modifier)
            .background(
                color = if (isActive) buttonColor.copy(alpha = 0.75f) else buttonColor,
                shape = RoundedCornerShape(if (compact) 14.dp else 26.dp),
            )
            .pointerInput(isActive, text) {
                detectTapGestures(
                    onPress = {
                        onPressStart()
                        val released = tryAwaitRelease()
                        onPressEnd(released)
                    },
                )
            }
            .padding(horizontal = if (compact) 12.dp else 18.dp, vertical = if (compact) 10.dp else 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = textColor,
            fontWeight = FontWeight.Bold,
            fontSize = if (compact) 13.sp else 18.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SettingsDialog(
    threshold: Float,
    playbackSpeed: Float,
    onDismiss: () -> Unit,
    onSaveThreshold: (Float) -> Unit,
    onSavePlaybackSpeed: (Float) -> Unit,
    onReset: () -> Unit,
) {
    var sliderValue by remember(threshold) { mutableStateOf(threshold) }
    var speedValue by remember(playbackSpeed) { mutableStateOf(playbackSpeed) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onSaveThreshold(sliderValue)
                onSavePlaybackSpeed(speedValue)
                onDismiss()
            }) {
                Text("保存设置")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onReset) {
                    Text("清空并重录")
                }
                TextButton(onClick = onDismiss) {
                    Text("关闭")
                }
            }
        },
        title = { Text("学习设置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("当前识别阈值用于本地模板匹配，首版默认已经适配小词表。")
                Slider(value = sliderValue, onValueChange = { sliderValue = it }, valueRange = 0.55f..0.9f)
                Text("阈值：${"%.2f".format(sliderValue)}")
                Text("播放速度用于试听和识别后播报。")
                Slider(value = speedValue, onValueChange = { speedValue = it }, valueRange = 0.5f..1.5f)
                Text("播放速度：${"%.2f".format(speedValue)}x")
                Text("如需重新录入全部词条，请使用下方按钮。")
            }
        },
    )
}

@Composable
private fun DialectCalculatorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme.copy(
            primary = Color(0xFF0D6E5D),
            secondary = Color(0xFFF59E0B),
            tertiary = Color(0xFF1D4ED8),
            surface = Color(0xFFF6FBF7),
            background = Color(0xFFF0F7FF),
        ),
        content = content,
    )
}
