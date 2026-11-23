package com.example.inputmonitor.ui.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private const val TEMPORARY_PIN = "123456"
private const val PIN_LENGTH = 6
private val keypadRows = listOf("789", "456", "123")

@Composable
internal fun PinScreen(onUnlock: () -> Unit, onBack: (() -> Unit)? = null) {
    var enteredPin by remember { mutableStateOf("") }
    var incorrectPin by remember { mutableStateOf(false) }

    fun appendDigit(digit: Char) {
        if (enteredPin.length < PIN_LENGTH) {
            enteredPin += digit
            incorrectPin = false
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding()
                .verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Column(
                modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Input Monitor", style = MaterialTheme.typography.headlineMedium)
                if (onBack != null) TextButton(onClick = onBack) { Text("Back to calculator") }
                Text("Enter your six-digit PIN")
                Text(
                    text = List(PIN_LENGTH) { if (it < enteredPin.length) "●" else "○" }.joinToString(" "),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics {
                        contentDescription = "${enteredPin.length} of $PIN_LENGTH digits entered"
                    }
                )
                if (incorrectPin) {
                    Text(
                        "Incorrect PIN. Try again.",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    )
                }
                for (row in keypadRows) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (digit in row) {
                            OutlinedButton(
                                onClick = { appendDigit(digit) },
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                            ) { Text(digit.toString()) }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { enteredPin = ""; incorrectPin = false },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    ) { Text("Clear") }
                    OutlinedButton(
                        onClick = { appendDigit('0') },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    ) { Text("0") }
                    OutlinedButton(
                        onClick = { enteredPin = enteredPin.dropLast(1) },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    ) { Text("Delete") }
                }
                Button(
                    onClick = {
                        if (enteredPin == TEMPORARY_PIN) {
                            onUnlock()
                        } else {
                            enteredPin = ""
                            incorrectPin = true
                        }
                    },
                    enabled = enteredPin.length == PIN_LENGTH,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) { Text("Enter") }
            }
        }
    }
}
