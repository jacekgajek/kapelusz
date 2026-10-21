package com.jacekgajek.artsplit.app

import android.content.Context
import com.jacekgajek.artsplit.app.auth.AndroidGoogleAuth
import com.jacekgajek.artsplit.app.data.AppGraph
import com.jacekgajek.artsplit.app.data.FileStore
import com.jacekgajek.artsplit.app.platform.AndroidPlatform
import java.io.File

/** Single [AppGraph] per process, so activity recreation (rotation) keeps the loaded state. */
object AndroidAppGraph {
    private var instance: AppGraph? = null

    fun get(context: Context): AppGraph {
        AndroidPlatform.context = context.applicationContext
        return instance ?: AppGraph(FileStore(File(context.applicationContext.filesDir, "art-split")), AndroidGoogleAuth).also { instance = it }
    }
}
