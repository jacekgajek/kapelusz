package com.jacekgajek.kapelusz.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.band_name
import com.jacekgajek.kapelusz.app.generated.create_band
import com.jacekgajek.kapelusz.app.generated.currency
import com.jacekgajek.kapelusz.app.generated.your_name
import com.jacekgajek.kapelusz.app.ui.components.Dropdown
import org.jetbrains.compose.resources.stringResource

private val CURRENCIES = listOf("PLN", "EUR", "USD", "GBP", "CZK")

/** Form to create a band; the user becomes its owner. Used on the first screen and from the band switcher. */
@Composable
fun CreateBandForm(onCreate: (name: String, currency: String, yourName: String) -> Unit, defaultYourName: String = "") {
    var name by remember { mutableStateOf("") }
    var yourName by remember { mutableStateOf(defaultYourName) }
    var currency by remember { mutableStateOf(CURRENCIES.first()) }
    Column(Modifier.widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(name, { name = it }, label = { Text(stringResource(Res.string.band_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(yourName, { yourName = it }, label = { Text(stringResource(Res.string.your_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Dropdown(currency, CURRENCIES, { currency = it }, stringResource(Res.string.currency)) { it }
        // Once only: the screen stays tappable while it fades out, and a second tap would create a second band.
        var created by remember { mutableStateOf(false) }
        Button(
            onClick = {
                if (!created) {
                    created = true
                    onCreate(name, currency, yourName)
                }
            },
            enabled = name.isNotBlank() && yourName.isNotBlank() && !created,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(Res.string.create_band)) }
    }
}
