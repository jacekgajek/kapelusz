package com.jacekgajek.artsplit.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jacekgajek.artsplit.app.generated.Res
import com.jacekgajek.artsplit.app.generated.app_name
import com.jacekgajek.artsplit.app.generated.band_name
import com.jacekgajek.artsplit.app.generated.create_band
import com.jacekgajek.artsplit.app.generated.create_band_intro
import com.jacekgajek.artsplit.app.generated.currency
import com.jacekgajek.artsplit.app.generated.your_name
import com.jacekgajek.artsplit.app.ui.components.Dropdown
import org.jetbrains.compose.resources.stringResource

private val CURRENCIES = listOf("PLN", "EUR", "USD", "GBP", "CZK")

/** First start: create a band; the user becomes its owner. */
@Composable
fun CreateBandScreen(onCreate: (name: String, currency: String, yourName: String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var yourName by remember { mutableStateOf("") }
    var currency by remember { mutableStateOf(CURRENCIES.first()) }
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(Res.string.app_name), style = MaterialTheme.typography.headlineLarge)
            Text(stringResource(Res.string.create_band_intro), style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(Res.string.band_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(yourName, { yourName = it }, label = { Text(stringResource(Res.string.your_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Dropdown(currency, CURRENCIES, { currency = it }, stringResource(Res.string.currency)) { it }
            Button(
                onClick = { onCreate(name, currency, yourName) },
                enabled = name.isNotBlank() && yourName.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(Res.string.create_band)) }
        }
    }
}
