package com.jacekgajek.kapelusz.app.platform

import android.annotation.SuppressLint
import android.content.Context

/** Application context for platform helpers that run outside composition; set by AndroidAppGraph. */
@SuppressLint("StaticFieldLeak")
object AndroidPlatform {
    var context: Context? = null
}
