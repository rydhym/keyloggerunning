package com.example.inputmonitor.ui.lock

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private val calculatorSaver = listSaver<CalculatorState, String>(
    save = { listOf(it.expression, it.answer.orEmpty(), it.error.orEmpty()) },
    restore = { CalculatorState(it[0], it[1].ifEmpty { null }, it[2].ifEmpty { null }) }
)
private val calculatorRows = listOf(
    listOf("AC", "⌫", "%", "÷"), listOf("7", "8", "9", "×"),
    listOf("4", "5", "6", "−"), listOf("1", "2", "3", "+"), listOf("±", "0", ".", "=")
)

@Composable
internal fun CalculatorScreen(onUnlock: () -> Unit) {
    var calculator by rememberSaveable(stateSaver = calculatorSaver) { mutableStateOf(CalculatorState()) }
    var showPin by rememberSaveable { mutableStateOf(false) }
    if (showPin) {
        BackHandler { showPin = false }
        PinScreen(onUnlock = onUnlock, onBack = { showPin = false })
        return
    }
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Column(Modifier.widthIn(max = 420.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Input Monitor", style = MaterialTheme.typography.titleLarge)
                Text("Calculator", style = MaterialTheme.typography.headlineMedium)
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Column(Modifier.fillMaxWidth().heightIn(min = 140.dp).padding(20.dp),
                        horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.Bottom) {
                        if (calculator.answer != null) Text(calculator.expression.replace('*', '×').replace('/', '÷').replace('-', '−'),
                            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End)
                        Text(calculator.display, style = if (calculator.error != null) MaterialTheme.typography.titleLarge
                            else MaterialTheme.typography.headlineLarge,
                            textAlign = TextAlign.End,
                            modifier = Modifier.fillMaxWidth().semantics {
                                liveRegion = LiveRegionMode.Polite
                                contentDescription = "Calculator display, ${calculator.display}"
                            })
                    }
                }
                for (row in calculatorRows) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (key in row) {
                            val description = when (key) {
                                "⌫" -> "Delete last character"; "±" -> "Change sign"; "AC" -> "Clear calculation"
                                "×" -> "Multiply"; "÷" -> "Divide"; "−" -> "Subtract"; "+" -> "Add"
                                "%" -> "Percent"; "=" -> "Equals"; "." -> "Decimal point"; else -> key
                            }
                            val action = key in listOf("+", "−", "×", "÷", "=")
                            Button(onClick = { calculator = calculator.press(key) },
                                modifier = Modifier.weight(1f).heightIn(min = 56.dp).semantics { contentDescription = description },
                                shape = RoundedCornerShape(18.dp),
                                contentPadding = PaddingValues(0.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (action) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = if (action) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer)) {
                                Text(key, style = MaterialTheme.typography.titleLarge)
                            }
                        }
                    }
                }
                OutlinedButton(
                    onClick = { showPin = true },
                    modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Text("Unlock Input Monitor", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
