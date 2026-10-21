package com.jacekgajek.artsplit.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import com.jacekgajek.artsplit.app.ui.RosterRoute
import com.jacekgajek.artsplit.app.ui.SettingsRoute
import com.jacekgajek.artsplit.app.ui.components.TabTopBar

/** Temporary tab content until the real screen exists. */
@Composable
fun PlaceholderScreen(title: String, navController: NavController) {
    Scaffold(topBar = { TabTopBar(title, { navController.navigate(SettingsRoute) }, { navController.navigate(RosterRoute) }) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Text(title) }
    }
}
