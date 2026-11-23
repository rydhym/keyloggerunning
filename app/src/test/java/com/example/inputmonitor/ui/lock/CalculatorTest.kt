package com.example.inputmonitor.ui.lock

import org.junit.Assert.*
import org.junit.Test

class CalculatorTest {
    private fun keys(vararg values: String): CalculatorState =
        values.fold(CalculatorState()) { state, key -> state.press(key) }

    @Test fun arithmeticUsesPrecedenceAndDecimalPrecision() {
        assertEquals("14", CalculatorMath.evaluate("2+3*4"))
        assertEquals("5", CalculatorMath.evaluate("20/2/2"))
        assertEquals("0.3", CalculatorMath.evaluate("0.1+0.2"))
        assertEquals("-6", CalculatorMath.evaluate("2*-3"))
        assertEquals("5", CalculatorMath.evaluate("2--3"))
    }

    @Test fun percentagesWorkForStandaloneMultiplicationAndRelativeChanges() {
        assertEquals("0.5", CalculatorMath.evaluate("50%"))
        assertEquals("20", CalculatorMath.evaluate("200*10%"))
        assertEquals("220", CalculatorMath.evaluate("200+10%"))
        assertEquals("180", CalculatorMath.evaluate("200-10%"))
    }

    @Test fun signAndDecimalKeysEditTheCurrentOperand() {
        assertEquals("-1.5", keys("2", "+", "3", "±", ".", "5", "=").answer)
        assertEquals("0.5", keys(".", "5", "=").answer)
        assertEquals("-0.5", keys("±", ".", "5", "=").answer)
        assertEquals("1.2", keys("1", ".", ".", "2", "=").answer)
    }

    @Test fun operatorsCanBeReplacedAndResultsCanBeUsedOrCleared() {
        val result = keys("2", "+", "×", "3", "=")
        assertEquals("6", result.answer)
        assertEquals("7", result.press("+").press("1").press("=").answer)
        assertEquals("9", result.press("9").expression)
        assertEquals(CalculatorState(), result.press("AC"))
    }

    @Test fun divisionByZeroAndIncompleteExpressionsRecover() {
        val failure = keys("1", "÷", "0", "=")
        assertEquals("Cannot divide by zero", failure.error)
        assertEquals("2", failure.press("2").press("=").answer)
        assertNotNull(keys("1", "+", "=").error)
    }

    @Test fun deleteWorksForAnOperandAndACompletedAnswer() {
        assertEquals("12", keys("1", "2", "3", "⌫").expression)
        assertEquals("1", keys("1", "2", "=", "⌫").expression)
        assertEquals("0", keys("⌫").display)
    }

    @Test fun pinDigitsAreOrdinaryCalculatorInput() {
        assertEquals("123456", keys("1", "2", "3", "4", "5", "6", "=").answer)
    }
}
