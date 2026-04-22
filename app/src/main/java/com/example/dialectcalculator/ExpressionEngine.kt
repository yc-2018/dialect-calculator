package com.example.dialectcalculator

import java.math.BigDecimal
import java.math.RoundingMode

class ExpressionEvaluator {

    fun parseAndEvaluate(tokens: List<TokenType>): EvaluationResult {
        val filteredTokens = tokens.filterNot { it == TokenType.Equals }
        if (filteredTokens.isEmpty()) {
            return EvaluationResult(
                expressionText = "",
                resultText = "",
                spokenSequence = emptyList(),
                errorMessage = "识别语音失败",
            )
        }

        val expression = mutableListOf<ExpressionPart>()
        var index = 0
        var expectNumber = true

        while (index < filteredTokens.size) {
            if (expectNumber) {
                val endIndex = findNumberEnd(filteredTokens, index)
                if (endIndex == index) {
                    return failure()
                }
                val numberTokens = filteredTokens.subList(index, endIndex)
                val number = parseNumber(numberTokens) ?: return failure()
                expression += ExpressionPart.Number(number, normalizeNumberTokens(numberTokens))
                index = endIndex
                expectNumber = false
            } else {
                val token = filteredTokens[index]
                if (!token.isOperator) {
                    return failure()
                }
                expression += ExpressionPart.Operator(token)
                index += 1
                expectNumber = true
            }
        }

        if (expression.isEmpty() || expression.last() !is ExpressionPart.Number) {
            return failure()
        }

        var result = (expression.first() as ExpressionPart.Number).value
        var pointer = 1
        while (pointer < expression.size) {
            val operator = expression[pointer] as? ExpressionPart.Operator ?: return failure()
            val next = expression.getOrNull(pointer + 1) as? ExpressionPart.Number ?: return failure()
            result = when (operator.token) {
                TokenType.Add -> result.add(next.value)
                TokenType.Subtract -> result.subtract(next.value)
                TokenType.Multiply -> result.multiply(next.value)
                TokenType.Divide -> {
                    if (next.value.compareTo(BigDecimal.ZERO) == 0) {
                        return EvaluationResult(
                            expressionText = expression.joinToString("") { it.display },
                            resultText = "",
                            spokenSequence = emptyList(),
                            errorMessage = "除数不能为零",
                        )
                    }
                    result.divide(next.value, 8, RoundingMode.HALF_UP)
                }

                else -> return failure()
            }
            pointer += 2
        }

        val expressionText = expression.joinToString("") { it.display }
        val normalizedResult = normalizeResult(result)
        return EvaluationResult(
            expressionText = expressionText,
            resultText = normalizedResult,
            spokenSequence = expressionPartsToPlaybackSequence(expression, normalizedResult),
            resultValue = result,
        )
    }

    private fun expressionPartsToPlaybackSequence(
        expression: List<ExpressionPart>,
        normalizedResult: String,
    ): List<TokenType> {
        val sequence = mutableListOf<TokenType>()
        expression.forEach { part ->
            when (part) {
                is ExpressionPart.Number -> sequence += part.playbackTokens
                is ExpressionPart.Operator -> sequence += part.token
            }
        }
        sequence += TokenType.Equals
        sequence += textToTokens(normalizedResult)
        return sequence
    }

    private fun failure() = EvaluationResult(
        expressionText = "",
        resultText = "",
        spokenSequence = emptyList(),
        errorMessage = "识别语音失败",
    )

    private fun findNumberEnd(tokens: List<TokenType>, start: Int): Int {
        var index = start
        while (index < tokens.size && !tokens[index].isOperator && tokens[index] != TokenType.Equals) {
            index += 1
        }
        return index
    }

    private fun normalizeNumberTokens(tokens: List<TokenType>): List<TokenType> = buildList {
        tokens.forEach { token ->
            if (token == TokenType.TwoAlt) {
                add(TokenType.Two)
            } else {
                add(token)
            }
        }
    }

    private fun parseNumber(tokens: List<TokenType>): BigDecimal? {
        if (tokens.isEmpty()) return null

        val normalized = normalizeNumberTokens(tokens)
        var sign = BigDecimal.ONE
        var body = normalized
        if (body.firstOrNull() == TokenType.Negative) {
            sign = BigDecimal(-1)
            body = body.drop(1)
        }
        if (body.isEmpty()) return null

        val dotIndex = body.indexOf(TokenType.Dot)
        val integerTokens = if (dotIndex == -1) body else body.take(dotIndex)
        val decimalTokens = if (dotIndex == -1) emptyList() else body.drop(dotIndex + 1)
        if (decimalTokens.any { !it.isDigit() }) return null
        if (body.count { it == TokenType.Dot } > 1) return null

        val integerPart = when {
            integerTokens.isEmpty() -> BigDecimal.ZERO
            integerTokens.all { it.isDigit() } -> {
                integerTokens.joinToString("") { it.digitValue().toString() }.toBigDecimal()
            }

            else -> parseChineseInteger(integerTokens)?.toBigDecimal()
        } ?: return null

        val decimalPart = if (decimalTokens.isEmpty()) {
            BigDecimal.ZERO
        } else {
            "0.${decimalTokens.joinToString("") { it.digitValue().toString() }}".toBigDecimal()
        }

        return sign.multiply(integerPart.add(decimalPart))
    }

    private fun parseChineseInteger(tokens: List<TokenType>): Int? {
        if (tokens.isEmpty()) return 0
        var result = 0
        var section = 0
        var number = 0
        var previousWasZero = false

        for (token in tokens) {
            when {
                token.isDigit() -> {
                    if (previousWasZero && number != 0) {
                        return null
                    }
                    number = token.digitValue()
                    previousWasZero = token == TokenType.Zero
                }

                token.unitValue() in listOf(10, 100, 1000) -> {
                    val unit = token.unitValue()
                    if (previousWasZero && number == 0) {
                        previousWasZero = false
                        continue
                    }
                    val digit = if (number == 0) 1 else number
                    section += digit * unit
                    number = 0
                    previousWasZero = false
                }

                token == TokenType.TenThousand -> {
                    section += number
                    if (section == 0) section = 1
                    result += section * 10_000
                    section = 0
                    number = 0
                    previousWasZero = false
                }

                else -> return null
            }
        }

        return result + section + number
    }

    fun normalizeResult(value: BigDecimal): String {
        val scaled = value.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros()
        val plain = scaled.toPlainString()
        val negative = plain.startsWith("-")
        val unsigned = plain.removePrefix("-")
        val parts = unsigned.split(".")
        val integerText = integerToText(parts[0].toLong())
        val decimalText = if (parts.size > 1 && parts[1].isNotEmpty()) {
            TokenType.Dot.displayText + parts[1].map { digitCharToToken(it).displayText }.joinToString("")
        } else {
            ""
        }
        return buildString {
            if (negative && scaled.compareTo(BigDecimal.ZERO) != 0) {
                append(TokenType.Negative.displayText)
            }
            append(integerText)
            append(decimalText)
        }
    }

    fun textToTokens(text: String): List<TokenType> = text.mapNotNull { char ->
        when (char) {
            '加' -> TokenType.Add
            '减' -> TokenType.Subtract
            '乘' -> TokenType.Multiply
            '除' -> TokenType.Divide
            '点' -> TokenType.Dot
            '负' -> TokenType.Negative
            '零' -> TokenType.Zero
            '壹' -> TokenType.One
            '贰' -> TokenType.Two
            '叁' -> TokenType.Three
            '肆' -> TokenType.Four
            '伍' -> TokenType.Five
            '陆' -> TokenType.Six
            '柒' -> TokenType.Seven
            '捌' -> TokenType.Eight
            '玖' -> TokenType.Nine
            '拾' -> TokenType.Ten
            '佰' -> TokenType.Hundred
            '仟' -> TokenType.Thousand
            '万' -> TokenType.TenThousand
            '等' -> null
            '于' -> null
            else -> null
        }
    }

    private fun integerToText(value: Long): String {
        if (value == 0L) return TokenType.Zero.displayText
        var remaining = value
        val sections = mutableListOf<Int>()
        while (remaining > 0) {
            sections += (remaining % 10_000).toInt()
            remaining /= 10_000
        }

        val sectionUnits = listOf("", TokenType.TenThousand.displayText, "亿")
        return buildString {
            for (index in sections.indices.reversed()) {
                val section = sections[index]
                if (section == 0) {
                    if (isNotEmpty() && !endsWith(TokenType.Zero.displayText)) {
                        append(TokenType.Zero.displayText)
                    }
                    continue
                }
                val sectionText = fourDigitSectionToText(section)
                if (isNotEmpty() && section < 1000 && !endsWith(TokenType.Zero.displayText)) {
                    append(TokenType.Zero.displayText)
                }
                append(sectionText)
                append(sectionUnits[index])
            }
        }.trimEnd(TokenType.Zero.displayText[0])
    }

    private fun fourDigitSectionToText(value: Int): String {
        if (value == 0) return ""
        val digits = listOf(
            value / 1000,
            (value / 100) % 10,
            (value / 10) % 10,
            value % 10,
        )
        val units = listOf(
            TokenType.Thousand.displayText,
            TokenType.Hundred.displayText,
            TokenType.Ten.displayText,
            "",
        )
        return buildString {
            var zeroPending = false
            digits.forEachIndexed { index, digit ->
                if (digit == 0) {
                    if (isNotEmpty()) {
                        zeroPending = true
                    }
                    return@forEachIndexed
                }
                if (zeroPending) {
                    append(TokenType.Zero.displayText)
                    zeroPending = false
                }
                append(digitToToken(digit).displayText)
                append(units[index])
            }
        }
    }

    private fun TokenType.isDigit(): Boolean = this in listOf(
        TokenType.Zero,
        TokenType.One,
        TokenType.Two,
        TokenType.Three,
        TokenType.Four,
        TokenType.Five,
        TokenType.Six,
        TokenType.Seven,
        TokenType.Eight,
        TokenType.Nine,
    )

    private fun TokenType.digitValue(): Int = when (this) {
        TokenType.Zero -> 0
        TokenType.One -> 1
        TokenType.Two, TokenType.TwoAlt -> 2
        TokenType.Three -> 3
        TokenType.Four -> 4
        TokenType.Five -> 5
        TokenType.Six -> 6
        TokenType.Seven -> 7
        TokenType.Eight -> 8
        TokenType.Nine -> 9
        else -> error("Not a digit token: $this")
    }

    private fun TokenType.unitValue(): Int = when (this) {
        TokenType.Ten -> 10
        TokenType.Hundred -> 100
        TokenType.Thousand -> 1000
        TokenType.TenThousand -> 10_000
        else -> -1
    }

    private fun digitToToken(value: Int): TokenType = when (value) {
        0 -> TokenType.Zero
        1 -> TokenType.One
        2 -> TokenType.Two
        3 -> TokenType.Three
        4 -> TokenType.Four
        5 -> TokenType.Five
        6 -> TokenType.Six
        7 -> TokenType.Seven
        8 -> TokenType.Eight
        9 -> TokenType.Nine
        else -> error("Unsupported digit: $value")
    }

    private fun digitCharToToken(char: Char): TokenType = digitToToken(char.digitToInt())

    private sealed interface ExpressionPart {
        val display: String

        data class Number(
            val value: BigDecimal,
            val playbackTokens: List<TokenType>,
        ) : ExpressionPart {
            override val display: String = playbackTokens.toNormalizedText()
        }

        data class Operator(val token: TokenType) : ExpressionPart {
            override val display: String = token.displayText
        }
    }
}
