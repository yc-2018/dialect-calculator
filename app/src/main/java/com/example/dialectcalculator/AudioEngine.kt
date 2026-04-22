package com.example.dialectcalculator

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

private const val SAMPLE_RATE = 16_000
private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

private fun writeWaveFile(file: File, pcmData: ByteArray) {
    file.parentFile?.mkdirs()
    FileOutputStream(file).use { output ->
        val totalAudioLength = pcmData.size.toLong()
        val totalDataLength = totalAudioLength + 36
        val byteRate = SAMPLE_RATE * 2L
        val header = ByteArray(44)
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        putIntLE(header, 4, totalDataLength.toInt())
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        putIntLE(header, 16, 16)
        putShortLE(header, 20, 1)
        putShortLE(header, 22, 1)
        putIntLE(header, 24, SAMPLE_RATE)
        putIntLE(header, 28, byteRate.toInt())
        putShortLE(header, 32, 2)
        putShortLE(header, 34, 16)
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        putIntLE(header, 40, totalAudioLength.toInt())
        output.write(header)
        output.write(pcmData)
    }
}

private fun putIntLE(buffer: ByteArray, offset: Int, value: Int) {
    buffer[offset] = (value and 0xff).toByte()
    buffer[offset + 1] = ((value shr 8) and 0xff).toByte()
    buffer[offset + 2] = ((value shr 16) and 0xff).toByte()
    buffer[offset + 3] = ((value shr 24) and 0xff).toByte()
}

private fun putShortLE(buffer: ByteArray, offset: Int, value: Int) {
    buffer[offset] = (value and 0xff).toByte()
    buffer[offset + 1] = ((value shr 8) and 0xff).toByte()
}

data class AudioFeature(
    val vector: List<Float>,
    val durationMs: Long,
    val qualityScore: Float,
)

data class RecognitionSegment(
    val startSample: Int,
    val endSample: Int,
    val samples: ShortArray,
)

class AudioRecorderEngine {
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val audioSource = MediaRecorder.AudioSource.MIC
    private val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, ENCODING)
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var outputFile: File? = null
    private val recordedBytes = ByteArrayOutputStream()

    suspend fun startRecording(targetFile: File) = withContext(Dispatchers.IO) {
        if (_isRecording.value) return@withContext

        outputFile = targetFile
        recordedBytes.reset()
        audioRecord = AudioRecord(
            audioSource,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            ENCODING,
            minBufferSize.coerceAtLeast(SAMPLE_RATE),
        ).also { record ->
            record.startRecording()
        }
        _isRecording.value = true
        recordingJob = launchRecordingLoop(audioRecord!!)
    }

    suspend fun stopRecording(): File? = withContext(Dispatchers.IO) {
        if (!_isRecording.value) return@withContext outputFile

        _isRecording.value = false
        recordingJob?.cancelAndJoin()
        val record = audioRecord
        audioRecord = null
        recordingJob = null

        record?.runCatching {
            stop()
        }
        record?.release()

        val file = outputFile ?: return@withContext null
        writeWaveFile(file, recordedBytes.toByteArray())
        outputFile = null
        file
    }

    private fun launchRecordingLoop(record: AudioRecord): Job {
        val scope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.IO)
        return scope.launch {
            val buffer = ByteArray(minBufferSize.coerceAtLeast(2048))
            while (isActive && _isRecording.value) {
                val size = record.read(buffer, 0, buffer.size)
                if (size > 0) {
                    recordedBytes.write(buffer, 0, size)
                }
            }
        }
    }

}

class FeatureExtractor {

    fun readSamples(file: File): ShortArray {
        FileInputStream(file).use { input ->
            val data = input.readBytes()
            if (data.size <= 44) return ShortArray(0)
            val pcmBytes = data.copyOfRange(44, data.size)
            return ByteBuffer.wrap(pcmBytes)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()
                .let { buffer ->
                    ShortArray(buffer.remaining()).also(buffer::get)
                }
        }
    }

    fun extractFeature(file: File): AudioFeature {
        val samples = readSamples(file)
        return extractFeature(samples)
    }

    fun extractFeature(samples: ShortArray): AudioFeature {
        val trimmed = trimSilence(samples)
        val normalized = if (trimmed.isEmpty()) samples else trimmed
        val durationMs = ((normalized.size.toDouble() / SAMPLE_RATE) * 1000).toLong()
        val vector = buildFeatureVector(normalized)
        val avgAmplitude = if (normalized.isEmpty()) 0f else {
            normalized.sumOf { abs(it.toInt()) }.toFloat() / normalized.size / Short.MAX_VALUE
        }
        val qualityScore = (avgAmplitude * 0.65f + (durationMs / 900f).coerceAtMost(0.35f)).coerceIn(0f, 1f)
        return AudioFeature(
            vector = vector,
            durationMs = durationMs,
            qualityScore = qualityScore,
        )
    }

    fun splitSegments(samples: ShortArray): List<RecognitionSegment> {
        val frameSize = SAMPLE_RATE / 50
        if (samples.isEmpty()) return emptyList()
        val frameCount = samples.size / frameSize
        if (frameCount == 0) return emptyList()

        val energies = FloatArray(frameCount) { index ->
            frameRms(samples, index * frameSize, (index + 1) * frameSize)
        }
        val maxEnergy = energies.maxOrNull() ?: 0f
        val meanEnergy = energies.average().toFloat()
        val threshold = maxOf(maxEnergy * 0.18f, meanEnergy * 1.35f, 0.01f)
        val maxGapFrames = 4
        val minSpeechFrames = 3
        val segments = mutableListOf<RecognitionSegment>()
        var speechStart = -1
        var silentFrames = 0

        for (frameIndex in 0 until frameCount) {
            val isSpeech = energies[frameIndex] >= threshold
            when {
                isSpeech && speechStart == -1 -> {
                    speechStart = frameIndex
                    silentFrames = 0
                }

                isSpeech -> {
                    silentFrames = 0
                }

                speechStart != -1 -> {
                    silentFrames += 1
                    if (silentFrames > maxGapFrames) {
                        val endFrame = frameIndex - silentFrames + 1
                        if (endFrame - speechStart >= minSpeechFrames) {
                            segments += createSegment(samples, speechStart, endFrame, frameSize)
                        }
                        speechStart = -1
                        silentFrames = 0
                    }
                }
            }
        }

        if (speechStart != -1) {
            val endFrame = frameCount
            if (endFrame - speechStart >= minSpeechFrames) {
                segments += createSegment(samples, speechStart, endFrame, frameSize)
            }
        }

        return segments
    }

    private fun createSegment(
        samples: ShortArray,
        startFrame: Int,
        endFrame: Int,
        frameSize: Int,
    ): RecognitionSegment {
        val padding = frameSize
        val start = (startFrame * frameSize - padding).coerceAtLeast(0)
        val end = (endFrame * frameSize + padding).coerceAtMost(samples.size)
        val slice = samples.copyOfRange(start, end)
        return RecognitionSegment(start, end, trimSilence(slice))
    }

    private fun trimSilence(samples: ShortArray): ShortArray {
        if (samples.isEmpty()) return samples
        val threshold = samples.maxOf { abs(it.toInt()) }.coerceAtLeast(1) * 0.12f
        var start = 0
        var end = samples.lastIndex
        while (start < samples.size && abs(samples[start].toInt()) < threshold) {
            start += 1
        }
        while (end >= start && abs(samples[end].toInt()) < threshold) {
            end -= 1
        }
        if (end <= start) return samples
        return samples.copyOfRange(start, end + 1)
    }

    private fun buildFeatureVector(samples: ShortArray): List<Float> {
        if (samples.isEmpty()) {
            return List(26) { 0f }
        }
        val bucketCount = 20
        val buckets = MutableList(bucketCount) { 0f }
        val bucketSizes = IntArray(bucketCount)
        val chunkSize = samples.size.toFloat() / bucketCount
        var zeroCrossings = 0

        for (index in samples.indices) {
            val bucketIndex = (index / chunkSize).toInt().coerceIn(0, bucketCount - 1)
            buckets[bucketIndex] += abs(samples[index].toInt()).toFloat() / Short.MAX_VALUE
            bucketSizes[bucketIndex] += 1
            if (index > 0 && samples[index].toInt().sign() != samples[index - 1].toInt().sign()) {
                zeroCrossings += 1
            }
        }

        for (index in buckets.indices) {
            if (bucketSizes[index] > 0) {
                buckets[index] /= bucketSizes[index]
            }
        }

        val mean = buckets.average().toFloat()
        val variance = buckets.map { (it - mean) * (it - mean) }.average().toFloat()
        val rms = sqrt(samples.map { it.toInt() * it.toInt().toDouble() }.average()).toFloat() / Short.MAX_VALUE
        val zcr = zeroCrossings.toFloat() / samples.size
        val duration = samples.size.toFloat() / SAMPLE_RATE

        return buildList {
            addAll(buckets)
            add(mean)
            add(variance)
            add(rms)
            add(zcr)
            add(duration)
            add(samples.first().toFloat() / Short.MAX_VALUE)
        }
    }

    private fun frameRms(samples: ShortArray, start: Int, end: Int): Float {
        if (start >= samples.size) return 0f
        val actualEnd = end.coerceAtMost(samples.size)
        var sum = 0.0
        var count = 0
        for (index in start until actualEnd) {
            val value = samples[index].toInt()
            sum += value * value.toDouble()
            count += 1
        }
        if (count == 0) return 0f
        return (sqrt(sum / count) / Short.MAX_VALUE).toFloat()
    }

    private fun Int.sign(): Int = when {
        this > 0 -> 1
        this < 0 -> -1
        else -> 0
    }
}

class TokenRecognizer(
    private val featureExtractor: FeatureExtractor,
) {
    fun recognize(
        inputFile: File,
        trainingSamples: List<TrainingSample>,
        threshold: Float,
    ): RecognitionResult {
        if (trainingSamples.isEmpty()) {
            return RecognitionResult(
                tokens = emptyList(),
                normalizedText = "",
                confidence = 0f,
                status = RecognitionStatus.Failure,
                errorMessage = "请先完成学习录音",
            )
        }

        val utteranceSamples = featureExtractor.readSamples(inputFile)
        val segments = featureExtractor.splitSegments(utteranceSamples)
        if (segments.isEmpty()) {
            return RecognitionResult(
                tokens = emptyList(),
                normalizedText = "",
                confidence = 0f,
                status = RecognitionStatus.Failure,
                errorMessage = "识别语音失败",
            )
        }

        val grouped = trainingSamples.groupBy { it.token }
        val recognized = mutableListOf<TokenType>()
        val segmentScores = mutableListOf<Float>()

        for (segment in segments) {
            val feature = featureExtractor.extractFeature(segment.samples)
            var bestToken: TokenType? = null
            var bestScore = -1f
            var secondScore = -1f

            grouped.forEach { (token, samples) ->
                val score = samples
                    .map { similarity(feature.vector, it.featureVector) }
                    .average()
                    .toFloat()
                if (score > bestScore) {
                    secondScore = bestScore
                    bestScore = score
                    bestToken = token
                } else if (score > secondScore) {
                    secondScore = score
                }
            }

            val token = bestToken
            val confidence = (bestScore * 0.75f + (bestScore - secondScore).coerceAtLeast(0f) * 0.25f)
                .coerceIn(0f, 1f)
            if (token == null || confidence < threshold) {
                return RecognitionResult(
                    tokens = emptyList(),
                    normalizedText = "",
                    confidence = confidence,
                    status = RecognitionStatus.Failure,
                    errorMessage = "识别语音失败",
                )
            }

            recognized += token
            segmentScores += confidence
        }

        return RecognitionResult(
            tokens = recognized,
            normalizedText = recognized.toNormalizedText(),
            confidence = if (segmentScores.isEmpty()) 0f else segmentScores.average().toFloat(),
            status = RecognitionStatus.Success,
        )
    }

    private fun similarity(left: List<Float>, right: List<Float>): Float {
        if (left.isEmpty() || right.isEmpty() || left.size != right.size) return 0f
        var dot = 0f
        var leftNorm = 0f
        var rightNorm = 0f
        for (index in left.indices) {
            dot += left[index] * right[index]
            leftNorm += left[index] * left[index]
            rightNorm += right[index] * right[index]
        }
        if (leftNorm == 0f || rightNorm == 0f) return 0f
        return (dot / (sqrt(leftNorm) * sqrt(rightNorm))).coerceIn(0f, 1f)
    }
}

class DialectPlaybackComposer(
    private val repository: TrainingRepository,
) {
    suspend fun composePlayback(
        sequence: List<TokenType>,
        samples: List<TrainingSample>,
    ): File = withContext(Dispatchers.IO) {
        val bestSamples = samples
            .groupBy { it.token }
            .mapValues { (_, value) -> value.maxByOrNull { it.qualityScore } }

        val output = ByteArrayOutputStream()
        sequence.forEach { token ->
            val sample = bestSamples[token] ?: return@forEach
            val pcm = extractPcm(File(sample.audioPath))
            output.write(pcm)
            repeat(SAMPLE_RATE / 12) {
                output.write(0)
                output.write(0)
            }
        }

        val file = repository.playbackOutputFile()
        writeWaveFile(file, output.toByteArray())
        file
    }

    suspend fun playFile(context: Context, file: File) = withContext(Dispatchers.Main) {
        val player = MediaPlayer()
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setLegacyStreamType(AudioManager.STREAM_MUSIC)
                .build(),
        )
        player.setDataSource(file.absolutePath)
        player.setOnCompletionListener {
            it.release()
        }
        player.prepare()
        player.start()
    }

    private fun extractPcm(file: File): ByteArray {
        FileInputStream(file).use { input ->
            val bytes = input.readBytes()
            return if (bytes.size > 44) bytes.copyOfRange(44, bytes.size) else ByteArray(0)
        }
    }
}
