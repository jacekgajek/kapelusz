package com.jacekgajek.kapelusz.app.data

import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

/** Time-ordered UUIDv7 entity id. */
@OptIn(ExperimentalUuidApi::class)
fun newId(): String = Uuid.generateV7().toString()

fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

fun today(): LocalDate = Clock.System.todayIn(TimeZone.currentSystemDefault())
