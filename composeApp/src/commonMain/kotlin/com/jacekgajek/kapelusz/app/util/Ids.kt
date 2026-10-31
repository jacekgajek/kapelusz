package com.jacekgajek.kapelusz.app.util

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Time-ordered UUIDv7 entity id. */
@OptIn(ExperimentalUuidApi::class)
fun newId(): String = Uuid.generateV7().toString()
