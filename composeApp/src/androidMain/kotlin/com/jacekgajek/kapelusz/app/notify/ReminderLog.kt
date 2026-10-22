package com.jacekgajek.kapelusz.app.notify

/**
 * What [ReminderScheduler] keeps in its preferences, kept free of Android so it can be tested (ITERATION_3 R14): the
 * scheduled reminders ("epochMillis|title", for the secret menu) and the reminders shown already ("shownAt|key").
 */
internal object ReminderLog {
    /** Only the newest shown reminders matter: a reminder is never due again more than a few hours after its time. */
    const val MAX_SHOWN = 200

    fun scheduledEntry(at: Long, eventName: String) = "$at|$eventName"

    /** The scheduled reminders, soonest first: when (epoch millis) and for which gig. */
    fun scheduled(entries: Set<String>): List<Pair<Long, String>> =
        entries.mapNotNull { entry -> entry.substringBefore('|').toLongOrNull()?.let { it to entry.substringAfter('|') } }.sortedBy { it.first }

    /** When each reminder (by key) was shown. */
    fun shownAt(entries: Set<String>): Map<String, Long> = entries.mapNotNull { entry ->
        val at = entry.substringBefore('|').toLongOrNull() ?: return@mapNotNull null
        entry.substringAfter('|') to at
    }.toMap()

    /** [entries] with [key] shown [now], dropping the oldest beyond [MAX_SHOWN]. */
    fun withShown(entries: Set<String>, key: String, now: Long): Set<String> =
        (shownAt(entries) + (key to now)).entries.sortedByDescending { it.value }.take(MAX_SHOWN).map { "${it.value}|${it.key}" }.toSet()
}
