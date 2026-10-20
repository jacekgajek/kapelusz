package com.jacekgajek.artsplit.model

import kotlinx.serialization.json.Json

/** JSON format shared by clients, server and S3 storage. Operations use the `type` discriminator. */
val ArtSplitJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
}
