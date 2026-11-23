package com.example.inputmonitor.ui.lock

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

internal data class CalculatorState(
    val expression: String = "",
    val answer: String? = null,
    val error: String? = null
) {
    val display: String get() = error ?: answer ?: expression.ifEmpty { "0" }
        .replace('*', '×').replace('/', '÷').replace('-', '−')

    fun press(key: String): CalculatorState {
        if (key == "AC") return CalculatorState()
        if (key == "=") {
            if (answer != null) return this
            return try {
                copy(answer = CalculatorMath.evaluate(expression.ifEmpty { "0" }), error = null)
            } catch (failure: IllegalArgumentException) {
                copy(error = failure.message ?: "Check the calculation")
            } catch (_: ArithmeticException) {
                copy(error = "Cannot calculate this result")
            }
        }
        val source = answer ?: expression
        val updated = when {
            key == "⌫" -> source.dropLast(1)
            key == "±" -> {
                val start = operandStart(source)
                val operand = source.substring(start)
                if (operand.startsWith('-')) source.removeRange(start, start + 1)
                else source.substring(0, start) + "-" + operand
            }
            key in listOf("+", "−", "×", "÷") -> {
                val operator = when (key) { "−" -> "-"; "×" -> "*"; "÷" -> "/"; else -> "+" }
                val number = source.trimEnd('+', '-', '*', '/')
                if (number.isEmpty()) return this
                number + operator
            }
            key == "%" -> if (source.lastOrNull()?.let { it.isDigit() || it == '.' } == true) source + "%" else return this
            key == "." || (key.length == 1 && key[0].isDigit()) -> {
                var input = if (answer != null || error != null) "" else expression
                if (input.endsWith('%')) return this
                val start = operandStart(input)
                val operand = input.substring(start)
                if (key == ".") {
                    if (operand.contains('.')) return this
                    input += if (operand.isEmpty() || operand == "-") "0." else "."
                } else {
                    if (operand == "0" || operand == "-0") input = input.dropLast(1)
                    input += key
                }
                input
            }
            else -> return this
        }
        return if (updated.length <= 128) CalculatorState(updated) else copy(error = "Calculation is too long")
    }

    private fun operandStart(value: String): Int {
        for (index in value.indices.reversed()) {
            if (value[index] in "+*/" || (value[index] == '-' && index > 0 && value[index - 1] !in "+-*/")) {
                return index + 1
            }
        }
        return 0
    }
}

/** Decimal arithmetic with multiplication/division precedence and contextual percentages. */
internal object CalculatorMath {
    private val precision = MathContext(16, RoundingMode.HALF_UP)

    fun evaluate(expression: String): String {
        val value = Parser(expression).evaluate().stripTrailingZeros()
        val formatted = value.toPlainString()
        require(formatted.length <= 128) { "Result is too large" }
        return formatted
    }

    private data class Term(val value: BigDecimal, val percent: Boolean)

    private class Parser(private val input: String) {
        private var position = 0

        fun evaluate(): BigDecimal {
            var result = term().value
            while (position < input.length) {
                val operator = input[position++]
                require(operator == '+' || operator == '-') { "Check the calculation" }
                val next = term()
                val amount = if (next.percent) result.multiply(next.value, precision) else next.value
                result = if (operator == '+') result.add(amount, precision) else result.subtract(amount, precision)
            }
            return result
        }

        private fun term(): Term {
            var result = number()
            while (position < input.length && input[position] in "*/") {
                val operator = input[position++]
                val next = number().value
                if (operator == '/') require(next.signum() != 0) { "Cannot divide by zero" }
                result = Term(if (operator == '*') result.value.multiply(next, precision)
                    else result.value.divide(next, precision), false)
            }
            return result
        }

        private fun number(): Term {
            val start = position
            if (position < input.length && input[position] == '-') position++
            while (position < input.length && (input[position].isDigit() || input[position] == '.')) position++
            val value = input.substring(start, position).toBigDecimalOrNull()
            require(value != null) { "Enter a number to finish the calculation" }
            val percent = position < input.length && input[position] == '%'
            if (percent) position++
            return Term(if (percent) value.divide(BigDecimal(100), precision) else value, percent)
        }
    }
}
