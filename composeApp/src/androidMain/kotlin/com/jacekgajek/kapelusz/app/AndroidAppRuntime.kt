package com.jacekgajek.kapelusz.app

import android.content.Context
import com.jacekgajek.kapelusz.app.auth.AndroidGoogleAuth
import com.jacekgajek.kapelusz.app.data.AppRuntime
import com.jacekgajek.kapelusz.app.data.FileStore
import com.jacekgajek.kapelusz.app.platform.AndroidPlatform
import com.jacekgajek.kapelusz.app.sync.FirebaseMessageChannel
import java.io.File

/** Single [AppRuntime] per process, so activity recreation (rotation) keeps the loaded state. */
object AndroidAppRuntime {
    private var instance: AppRuntime? = null

    /** Synchronized: a background job and the activity may ask at the same moment, and two runtimes would both write. */
    @Synchronized
    fun get(context: Context): AppRuntime {
        val app = context.applicationContext
        AndroidPlatform.context = app
        val store = FileStore(File(app.filesDir, "kapelusz"))
        return instance ?: AppRuntime(store, AndroidGoogleAuth, messageChannel = FirebaseMessageChannel(app)).also { instance = it }
    }
}
