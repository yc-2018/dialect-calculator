package com.example.dialectcalculator

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class AppUiState(
    val isLoading: Boolean = true,
    val hasRecordPermission: Boolean = false,
    val trainingItems: List<TrainingTokenState> = TrainingVocabulary.map { token ->
        TrainingTokenState(
            token = token,
            attempts = List(TrainingRepository.TRAINING_ATTEMPTS) { TrainingAttemptState(it) },
        )
    },
    val isTrainingComplete: Boolean = false,
    val lastResultText: String = "完成学习后即可开始识别",
    val lastRecognitionConfidence: Float? = null,
    val errorMessage: String? = null,
    val isRecording: Boolean = false,
    val activeMode: RecordingMode? = null,
    val currentThreshold: Float = TrainingRepository.DEFAULT_THRESHOLD,
) {
    val completedCount: Int
        get() = trainingItems.sumOf { it.completedAttempts }

    val totalCount: Int
        get() = trainingItems.size * TrainingRepository.TRAINING_ATTEMPTS

    val currentTrainingToken: TrainingTokenState?
        get() = trainingItems.firstOrNull { !it.isComplete }
}

enum class RecordingMode {
    Training,
    Recognition,
}

class DialectCalculatorViewModel(
    private val repository: TrainingRepository,
    private val recorder: AudioRecorderEngine,
    private val featureExtractor: FeatureExtractor,
    private val recognizer: TokenRecognizer,
    private val evaluator: ExpressionEvaluator,
    private val playbackComposer: DialectPlaybackComposer,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppUiState())
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()

    private var currentRecordingTarget: File? = null

    init {
        refresh()
        viewModelScope.launch {
            recorder.isRecording.collect { isRecording ->
                _uiState.update { state ->
                    state.copy(isRecording = isRecording)
                }
            }
        }
    }

    fun updatePermission(granted: Boolean) {
        _uiState.update { it.copy(hasRecordPermission = granted) }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val persisted = repository.load()
            _uiState.update { state ->
                state.copy(
                    isLoading = false,
                    trainingItems = mapTrainingItems(persisted.samples),
                    isTrainingComplete = persisted.trainingCompleted,
                    currentThreshold = persisted.threshold,
                    lastResultText = if (persisted.trainingCompleted) {
                        state.lastResultText
                    } else {
                        "请先完成全部词语学习"
                    },
                )
            }
        }
    }

    fun startTrainingRecording() {
        val current = _uiState.value.currentTrainingToken ?: return
        val attempt = current.attempts.firstOrNull { it.sample == null } ?: return
        val target = repository.trainingFile(current.token, attempt.attemptIndex)
        currentRecordingTarget = target
        _uiState.update { it.copy(activeMode = RecordingMode.Training, errorMessage = null) }
        viewModelScope.launch {
            recorder.startRecording(target)
        }
    }

    fun stopTrainingRecording() {
        viewModelScope.launch {
            val file = recorder.stopRecording() ?: return@launch
            currentRecordingTarget = null
            val currentToken = _uiState.value.currentTrainingToken ?: return@launch
            val attempt = currentToken.attempts.firstOrNull { it.sample == null } ?: return@launch
            val feature = featureExtractor.extractFeature(file)
            val sample = TrainingSample(
                token = currentToken.token,
                attemptIndex = attempt.attemptIndex,
                audioPath = file.absolutePath,
                featureVector = feature.vector,
                durationMs = feature.durationMs,
                qualityScore = feature.qualityScore,
            )
            repository.saveSample(sample)
            val updated = repository.load()
            _uiState.update {
                it.copy(
                    activeMode = null,
                    trainingItems = mapTrainingItems(updated.samples),
                    isTrainingComplete = updated.trainingCompleted,
                    lastResultText = if (updated.trainingCompleted) {
                        "按住按钮录音，松开后开始识别"
                    } else {
                        "继续完成学习录音"
                    },
                )
            }
        }
    }

    fun rerecord(token: TokenType, attemptIndex: Int) {
        val target = repository.trainingFile(token, attemptIndex)
        currentRecordingTarget = target
        _uiState.update {
            it.copy(
                activeMode = RecordingMode.Training,
                errorMessage = "准备重录 ${token.displayText} 第 ${attemptIndex + 1} 遍",
            )
        }
        viewModelScope.launch {
            recorder.startRecording(target)
        }
    }

    fun finishRerecord(token: TokenType, attemptIndex: Int) {
        viewModelScope.launch {
            val file = recorder.stopRecording() ?: return@launch
            currentRecordingTarget = null
            val feature = featureExtractor.extractFeature(file)
            repository.saveSample(
                TrainingSample(
                    token = token,
                    attemptIndex = attemptIndex,
                    audioPath = file.absolutePath,
                    featureVector = feature.vector,
                    durationMs = feature.durationMs,
                    qualityScore = feature.qualityScore,
                ),
            )
            val updated = repository.load()
            _uiState.update {
                it.copy(
                    activeMode = null,
                    trainingItems = mapTrainingItems(updated.samples),
                    isTrainingComplete = updated.trainingCompleted,
                    errorMessage = null,
                )
            }
        }
    }

    fun playTrainingSample(context: Context, sample: TrainingSample?) {
        if (sample == null) return
        viewModelScope.launch {
            playbackComposer.playFile(context, File(sample.audioPath))
        }
    }

    fun startRecognitionRecording() {
        if (!_uiState.value.isTrainingComplete) return
        val target = repository.recognitionInputFile()
        currentRecordingTarget = target
        _uiState.update { it.copy(activeMode = RecordingMode.Recognition, errorMessage = null) }
        viewModelScope.launch {
            recorder.startRecording(target)
        }
    }

    fun stopRecognitionRecording(context: Context) {
        if (_uiState.value.activeMode != RecordingMode.Recognition) return
        viewModelScope.launch {
            val file = recorder.stopRecording() ?: return@launch
            currentRecordingTarget = null
            val persisted = repository.load()
            val recognition = recognizer.recognize(file, persisted.samples, persisted.threshold)
            if (recognition.status == RecognitionStatus.Failure) {
                _uiState.update {
                    it.copy(
                        activeMode = null,
                        lastRecognitionConfidence = recognition.confidence,
                        errorMessage = recognition.errorMessage ?: "识别语音失败",
                    )
                }
                return@launch
            }

            val evaluation = evaluator.parseAndEvaluate(recognition.tokens)
            if (evaluation.errorMessage != null) {
                _uiState.update {
                    it.copy(
                        activeMode = null,
                        lastRecognitionConfidence = recognition.confidence,
                        errorMessage = evaluation.errorMessage,
                        lastResultText = evaluation.errorMessage,
                    )
                }
                return@launch
            }

            val playback = playbackComposer.composePlayback(
                evaluation.spokenSequence,
                persisted.samples,
            )
            playbackComposer.playFile(context, playback)
            _uiState.update {
                it.copy(
                    activeMode = null,
                    lastRecognitionConfidence = recognition.confidence,
                    errorMessage = null,
                    lastResultText = evaluation.fullText,
                )
            }
        }
    }

    fun resetTraining() {
        viewModelScope.launch {
            repository.reset()
            _uiState.value = AppUiState(hasRecordPermission = _uiState.value.hasRecordPermission, isLoading = false)
        }
    }

    fun updateThreshold(threshold: Float) {
        viewModelScope.launch {
            repository.setThreshold(threshold)
            _uiState.update { it.copy(currentThreshold = threshold) }
        }
    }

    private fun mapTrainingItems(samples: List<TrainingSample>): List<TrainingTokenState> {
        return TrainingVocabulary.map { token ->
            TrainingTokenState(
                token = token,
                attempts = List(TrainingRepository.TRAINING_ATTEMPTS) { attemptIndex ->
                    TrainingAttemptState(
                        attemptIndex = attemptIndex,
                        sample = samples.firstOrNull {
                            it.token == token && it.attemptIndex == attemptIndex
                        },
                    )
                },
            )
        }
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val repository = TrainingRepository(context.applicationContext)
            val featureExtractor = FeatureExtractor()
            return DialectCalculatorViewModel(
                repository = repository,
                recorder = AudioRecorderEngine(),
                featureExtractor = featureExtractor,
                recognizer = TokenRecognizer(featureExtractor),
                evaluator = ExpressionEvaluator(),
                playbackComposer = DialectPlaybackComposer(repository),
            ) as T
        }
    }
}
