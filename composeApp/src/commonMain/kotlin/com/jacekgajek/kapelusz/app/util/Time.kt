package com.jacekgajek.kapelusz.app.util

import kotlin.time.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

fun today(): LocalDate = Clock.System.todayIn(TimeZone.currentSystemDefault())
