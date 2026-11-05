package com.jacekgajek.kapelusz.app.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.integer_invalid
import org.jetbrains.compose.resources.stringResource

/** Decimal input for amounts; [onValid] receives the parsed amount or null when the text is not a valid amount. */
@Composable
fun IntegerField(
    text: String,
    onText: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    allowNegative: Boolean = false,
) {
    val parsed = text.toIntOrNull()
    val invalid = text.isNotBlank() && (parsed == null || (!allowNegative && parsed < 0))
    OutlinedTextField(
        value = text,
        onValueChange = onText,
        label = { Text(label) },
        isError = invalid,
        supportingText = if (invalid) ({ Text(stringResource(Res.string.integer_invalid)) }) else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
    )
}
