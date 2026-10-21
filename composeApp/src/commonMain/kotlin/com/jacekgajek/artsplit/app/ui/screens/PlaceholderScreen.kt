package com.jacekgajek.artsplit.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.jacekgajek.artsplit.app.ui.components.TabTopBar

/** Temporary content until the real screen exists. */
@Composable
fun PlaceholderScreen(title: String, onSettings: () -> Unit, onRoster: () -> Unit) {
    Scaffold(topBar = { TabTopBar(title, onSettings, onRoster) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Text(title) }
    }
}
