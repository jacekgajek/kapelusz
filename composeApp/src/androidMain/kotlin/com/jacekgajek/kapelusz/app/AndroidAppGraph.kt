package com.jacekgajek.kapelusz.app

import android.content.Context
import com.jacekgajek.kapelusz.app.auth.AndroidGoogleAuth
import com.jacekgajek.kapelusz.app.data.AppGraph
import com.jacekgajek.kapelusz.app.data.FileStore
import com.jacekgajek.kapelusz.app.platform.AndroidPlatform
import com.jacekgajek.kapelusz.app.sync.FirebaseMessageChannel
import java.io.File

/** Single [AppGraph] per process, so activity recreation (rotation) keeps the loaded state. */
object AndroidAppGraph {
    private var instance: AppGraph? = null

    /** Synchronized: a background job and the activity may ask at the same moment, and two graphs would both write. */
    @Synchronized
    fun get(context: Context): AppGraph {
        val app = context.applicationContext
        AndroidPlatform.context = app
        return instance ?: AppGraph(FileStore(File(app.filesDir, "kapelusz")), AndroidGoogleAuth, messages = FirebaseMessageChannel(app)).also { instance = it }
    }
}
