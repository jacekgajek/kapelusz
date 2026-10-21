package com.jacekgajek.artsplit.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.jacekgajek.artsplit.app.AndroidAppGraph
import com.jacekgajek.artsplit.app.App

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = AndroidAppGraph.get(this)
        setContent {
            var loaded by remember { mutableStateOf(false) }
            LaunchedEffect(graph) {
                graph.load()
                loaded = true
            }
            if (loaded) App(graph)
        }
    }
}
