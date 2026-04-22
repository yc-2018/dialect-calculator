package com.example.dialectcalculator

import java.math.BigDecimal

enum class TokenType(
    val displayText: String,
    val isOperator: Boolean = false,
    val isNumberPart: Boolean = false,
) {
    Add("加", isOperator = true),
    Subtract("减", isOperator = true),
    Multiply("乘", isOperator = true),
    Divide("除", isOperator = true),
    Dot("点", isNumberPart = true),
    Negative("负", isNumberPart = true),
    Zero("零", isNumberPart = true),
    One("壹", isNumberPart = true),
    Two("贰", isNumberPart = true),
    TwoAlt("两", isNumberPart = true),
    Three("叁", isNumberPart = true),
    Four("肆", isNumberPart = true),
    Five("伍", isNumberPart = true),
    Six("陆", isNumberPart = true),
    Seven("柒", isNumberPart = true),
    Eight("捌", isNumberPart = true),
    Nine("玖", isNumberPart = true),
    Ten("拾", isNumberPart = true),
    Hundred("佰", isNumberPart = true),
    Thousand("仟", isNumberPart = true),
    TenThousand("万", isNumberPart = true),
    Equals("等于"),
}

enum class RecognitionStatus {
    Success,
    Failure,
}

data class TrainingSample(
    val token: TokenType,
    val attemptIndex: Int,
    val audioPath: String,
    val featureVector: List<Float>,
    val durationMs: Long,
    val qualityScore: Float,
)

data class RecognitionResult(
    val tokens: List<TokenType>,
    val normalizedText: String,
    val confidence: Float,
    val status: RecognitionStatus,
    val errorMessage: String? = null,
)

data class EvaluationResult(
    val expressionText: String,
    val resultText: String,
    val spokenSequence: List<TokenType>,
    val resultValue: BigDecimal? = null,
    val errorMessage: String? = null,
) {
    val fullText: String
        get() = if (errorMessage == null) {
            expressionText + TokenType.Equals.displayText + resultText
        } else {
            errorMessage
        }
}

data class PersistedTrainingData(
    val trainingCompleted: Boolean,
    val threshold: Float,
    val playbackSpeed: Float,
    val samples: List<TrainingSample>,
)

data class TrainingAttemptState(
    val attemptIndex: Int,
    val sample: TrainingSample? = null,
)

data class TrainingTokenState(
    val token: TokenType,
    val attempts: List<TrainingAttemptState>,
) {
    val completedAttempts: Int
        get() = attempts.count { it.sample != null }

    val isComplete: Boolean
        get() = completedAttempts == attempts.size
}

val TrainingVocabulary: List<TokenType> = listOf(
    TokenType.Add,
    TokenType.Subtract,
    TokenType.Multiply,
    TokenType.Divide,
    TokenType.Dot,
    TokenType.Negative,
    TokenType.Zero,
    TokenType.One,
    TokenType.Two,
    TokenType.TwoAlt,
    TokenType.Three,
    TokenType.Four,
    TokenType.Five,
    TokenType.Six,
    TokenType.Seven,
    TokenType.Eight,
    TokenType.Nine,
    TokenType.Ten,
    TokenType.Hundred,
    TokenType.Thousand,
    TokenType.TenThousand,
    TokenType.Equals,
)

fun TokenType.normalizedDisplay(): String = when (this) {
    TokenType.TwoAlt -> TokenType.Two.displayText
    else -> displayText
}

fun List<TokenType>.toNormalizedText(includeEquals: Boolean = false): String = buildString {
    for (token in this@toNormalizedText) {
        if (!includeEquals && token == TokenType.Equals) {
            continue
        }
        append(token.normalizedDisplay())
    }
}
