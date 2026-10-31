package com.jacekgajek.kapelusz.history

import com.jacekgajek.kapelusz.ops.Operation
import com.jacekgajek.kapelusz.ops.UnknownOperation
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic

/** JSON format shared by clients, server and S3 storage. Operations use the `type` discriminator. */
val KapeluszJson = Json {
    ignoreUnknownKeys = true
    // An enum value from a newer version (e.g. a new kind of agenda time) falls back to the field's default instead
    // of making the whole band unreadable.
    coerceInputValues = true
    encodeDefaults = false
    // An operation of a kind this version doesn't know is read as such, not an unreadable history.
    serializersModule = SerializersModule {
        polymorphic(Operation::class) { defaultDeserializer { UnknownOperation.serializer() } }
    }
}
