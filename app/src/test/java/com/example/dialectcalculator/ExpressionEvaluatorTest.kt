package com.example.dialectcalculator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExpressionEvaluatorTest {

    private val evaluator = ExpressionEvaluator()

    @Test
    fun `one plus one point one`() {
        val result = evaluator.parseAndEvaluate(
            listOf(
                TokenType.One,
                TokenType.Add,
                TokenType.One,
                TokenType.Dot,
                TokenType.One,
            ),
        )

        assertNull(result.errorMessage)
        assertEquals("壹加壹点壹", result.expressionText)
        assertEquals("贰点壹", result.resultText)
        assertEquals("壹加壹点壹等于贰点壹", result.fullText)
    }

    @Test
    fun `negative multiply`() {
        val result = evaluator.parseAndEvaluate(
            listOf(
                TokenType.Negative,
                TokenType.Two,
                TokenType.Multiply,
                TokenType.Three,
            ),
        )

        assertNull(result.errorMessage)
        assertEquals("负贰乘叁", result.expressionText)
        assertEquals("负陆", result.resultText)
    }

    @Test
    fun `left to right evaluation`() {
        val result = evaluator.parseAndEvaluate(
            listOf(
                TokenType.One,
                TokenType.Add,
                TokenType.Two,
                TokenType.Multiply,
                TokenType.Three,
            ),
        )

        assertNull(result.errorMessage)
        assertEquals("玖", result.resultText)
    }

    @Test
    fun `supports liang alias`() {
        val result = evaluator.parseAndEvaluate(
            listOf(
                TokenType.One,
                TokenType.Hundred,
                TokenType.Zero,
                TokenType.Two,
                TokenType.Add,
                TokenType.TwoAlt,
            ),
        )

        assertNull(result.errorMessage)
        assertEquals("壹佰零贰加贰", result.expressionText)
        assertEquals("壹佰零肆", result.resultText)
    }

    @Test
    fun `divide keeps four decimals max`() {
        val result = evaluator.parseAndEvaluate(
            listOf(
                TokenType.Nine,
                TokenType.Divide,
                TokenType.Four,
            ),
        )

        assertNull(result.errorMessage)
        assertEquals("贰点贰伍", result.resultText)
    }

    @Test
    fun `divide by zero fails`() {
        val result = evaluator.parseAndEvaluate(
            listOf(
                TokenType.Five,
                TokenType.Divide,
                TokenType.Zero,
            ),
        )

        assertEquals("除数不能为零", result.errorMessage)
    }

    @Test
    fun `double operator fails`() {
        val result = evaluator.parseAndEvaluate(
            listOf(
                TokenType.One,
                TokenType.Add,
                TokenType.Subtract,
                TokenType.Two,
            ),
        )

        assertEquals("识别语音失败", result.errorMessage)
    }
}
