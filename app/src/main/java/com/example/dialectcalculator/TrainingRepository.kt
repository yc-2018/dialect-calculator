package com.example.dialectcalculator

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class TrainingRepository(private val context: Context) {

    private val rootDir: File = File(context.filesDir, "dialect_calculator")
    private val sampleDir: File = File(rootDir, "samples")
    private val metadataFile: File = File(rootDir, "training.json")

    suspend fun load(): PersistedTrainingData = withContext(Dispatchers.IO) {
        if (!metadataFile.exists()) {
            return@withContext PersistedTrainingData(
                trainingCompleted = false,
                threshold = DEFAULT_THRESHOLD,
                samples = emptyList(),
            )
        }

        val json = JSONObject(metadataFile.readText())
        val samplesArray = json.optJSONArray("samples") ?: JSONArray()
        val samples = buildList {
            for (index in 0 until samplesArray.length()) {
                val item = samplesArray.getJSONObject(index)
                add(
                    TrainingSample(
                        token = TokenType.valueOf(item.getString("token")),
                        attemptIndex = item.getInt("attemptIndex"),
                        audioPath = item.getString("audioPath"),
                        featureVector = item.getJSONArray("featureVector").toFloatList(),
                        durationMs = item.getLong("durationMs"),
                        qualityScore = item.getDouble("qualityScore").toFloat(),
                    ),
                )
            }
        }

        PersistedTrainingData(
            trainingCompleted = json.optBoolean("trainingCompleted", false),
            threshold = json.optDouble("threshold", DEFAULT_THRESHOLD.toDouble()).toFloat(),
            samples = samples,
        )
    }

    suspend fun saveSample(sample: TrainingSample) = withContext(Dispatchers.IO) {
        rootDir.mkdirs()
        sampleDir.mkdirs()
        val current = load()
        val updatedSamples = current.samples
            .filterNot { it.token == sample.token && it.attemptIndex == sample.attemptIndex } +
            sample
        val completed = TrainingVocabulary.all { token ->
            updatedSamples.count { it.token == token } >= TRAINING_ATTEMPTS
        }
        write(
            PersistedTrainingData(
                trainingCompleted = completed,
                threshold = current.threshold,
                samples = updatedSamples.sortedWith(compareBy({ it.token.ordinal }, { it.attemptIndex })),
            ),
        )
    }

    suspend fun setThreshold(threshold: Float) = withContext(Dispatchers.IO) {
        val current = load()
        write(current.copy(threshold = threshold))
    }

    suspend fun reset() = withContext(Dispatchers.IO) {
        if (rootDir.exists()) {
            rootDir.deleteRecursively()
        }
    }

    fun trainingFile(token: TokenType, attemptIndex: Int): File {
        sampleDir.mkdirs()
        return File(sampleDir, "${token.name.lowercase()}_$attemptIndex.wav")
    }

    fun playbackOutputFile(): File {
        rootDir.mkdirs()
        return File(rootDir, "playback.wav")
    }

    fun recognitionInputFile(): File {
        rootDir.mkdirs()
        return File(rootDir, "recognition_input.wav")
    }

    private fun write(data: PersistedTrainingData) {
        rootDir.mkdirs()
        sampleDir.mkdirs()
        val json = JSONObject()
            .put("trainingCompleted", data.trainingCompleted)
            .put("threshold", data.threshold.toDouble())
            .put(
                "samples",
                JSONArray().apply {
                    data.samples.forEach { sample ->
                        put(
                            JSONObject()
                                .put("token", sample.token.name)
                                .put("attemptIndex", sample.attemptIndex)
                                .put("audioPath", sample.audioPath)
                                .put("durationMs", sample.durationMs)
                                .put("qualityScore", sample.qualityScore.toDouble())
                                .put("featureVector", JSONArray(sample.featureVector)),
                        )
                    }
                },
            )
        metadataFile.writeText(json.toString())
    }

    private fun JSONArray.toFloatList(): List<Float> = buildList {
        for (index in 0 until length()) {
            add(getDouble(index).toFloat())
        }
    }

    companion object {
        const val TRAINING_ATTEMPTS = 3
        const val DEFAULT_THRESHOLD = 0.72f
    }
}
