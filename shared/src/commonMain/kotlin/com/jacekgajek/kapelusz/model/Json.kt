package com.jacekgajek.kapelusz.model

import kotlinx.serialization.json.Json

/** JSON format shared by clients, server and S3 storage. Operations use the `type` discriminator. */
val KapeluszJson = Json {
    ignoreUnknownKeys = true
    // An enum value from a newer version (e.g. a new kind of agenda time) falls back to the field's default instead
    // of making the whole band unreadable.
    coerceInputValues = true
    encodeDefaults = false
}
