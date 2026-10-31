package com.jacekgajek.kapelusz.server

/**
 * What a deployment decides about the API. Read from the environment only where a server is started, the Lambda's
 * [Handler] and the local server's `main`; tests and everything else pass what they mean.
 */
data class ServerConfig(
    /** Apps older than this version code must update first ([Api.MIN_APP_VERSION] in production); 0: no minimum. */
    val minAppVersion: Int = 0,
    /** The largest band file a push may produce, in UTF-8 bytes. */
    val maxBandBytes: Int = Api.MAX_BAND_BYTES,
    /** The web app's address, for the links in calendar entries. */
    val webUrl: String = WEB_URL,
) {
    companion object {
        const val WEB_URL = "https://kapelusz.jacekgajek.com"
    }
}
