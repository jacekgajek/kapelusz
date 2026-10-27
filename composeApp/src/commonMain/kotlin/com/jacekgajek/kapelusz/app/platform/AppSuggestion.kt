package com.jacekgajek.kapelusz.app.platform

import kotlinx.serialization.Serializable

/**
 * `/download/version.json` on the website, written by every deploy: the deployed version, so a web app open since
 * before it offers a reload (ITERATION_3 §2.3).
 */
@Serializable
data class PublishedVersion(val versionCode: Long, val versionName: String)
