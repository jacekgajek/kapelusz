package com.jacekgajek.artsplit.app

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import androidx.navigation.ExperimentalBrowserHistoryApi
import androidx.navigation.bindToNavigation
import com.jacekgajek.artsplit.app.data.AppGraph
import com.jacekgajek.artsplit.app.data.IndexedDbStore
import kotlinx.browser.window
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalComposeUiApi::class, ExperimentalBrowserHistoryApi::class)
fun main() {
    val graph = AppGraph(IndexedDbStore())
    MainScope().launch {
        graph.load()
        ComposeViewport {
            App(graph, onNavHostReady = { window.bindToNavigation(it) })
        }
    }
}
