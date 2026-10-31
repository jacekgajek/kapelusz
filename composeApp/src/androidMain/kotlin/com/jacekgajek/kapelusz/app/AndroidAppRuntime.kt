package com.jacekgajek.kapelusz.app

import android.content.Context
import com.jacekgajek.kapelusz.app.data.AppRuntime
import com.jacekgajek.kapelusz.app.platform.AndroidPlatform

/** Single [AppRuntime] (and [AndroidPlatform]) per process, so activity recreation (rotation) keeps the loaded state. */
object AndroidAppRuntime {
    private var androidPlatform: AndroidPlatform? = null
    private var instance: AppRuntime? = null

    /** Synchronized: a background job and the activity may ask at the same moment, and two runtimes would both write. */
    @Synchronized
    fun get(context: Context): AppRuntime = instance ?: AppRuntime(platform(context)).also { instance = it }

    @Synchronized
    fun platform(context: Context): AndroidPlatform {
        val made = androidPlatform ?: AndroidPlatform(context.applicationContext)
        androidPlatform = made
        return made
    }
}
