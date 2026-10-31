package com.jacekgajek.kapelusz.domain

import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Event
import com.jacekgajek.kapelusz.model.Member
import com.jacekgajek.kapelusz.model.Role
import kotlin.math.round
import kotlinx.datetime.LocalDate

/** "Where we played" (ITERATION_7 §8): the places of a band's concerts, for a map or a list. */
object PlayedPlaces {
    /** An address as looked up and cached: case and spacing don't make it another place. */
    fun key(location: String): String = location.trim().replace(Regex("\\s+"), " ").lowercase()

    /** The events [me] sees: an artist only those they play in. */
    fun visible(band: Band, me: Member?): List<Event> = band.events.filter { me?.role != Role.ARTIST || it.participant(me.id) != null }

    /** Concerts in [from]..[to] (open ends: all) already played by [today], with a location; gigs to come never. */
    fun played(band: Band, me: Member?, from: LocalDate?, to: LocalDate?, today: LocalDate): List<Event> = visible(band, me).filter { event ->
        event.date <= today && (from == null || event.date >= from) && (to == null || event.date <= to) && !event.location.isNullOrBlank()
    }

    /** The locations to look up: those without a pin of their own, once each. */
    fun toLocate(events: List<Event>): List<String> = events.mapNotNull { it.location?.takeIf(String::isNotBlank) }.filter { Places.pinIn(it) == null }.distinctBy(::key)

    /**
     * One place on the map: concerts whose coordinates match to about 10 m. [title] is the newest concert's location up
     * to its first comma (the venue, else the street), [address] that location whole.
     */
    data class Place(val latitude: Double, val longitude: Double, val title: String, val address: String, val events: List<Event>)

    /** [events] as places (newest concert first in each, places by how often played), and those with no coordinates. */
    fun places(events: List<Event>, coordinates: (String) -> Pair<Double, Double>?): Pair<List<Place>, List<Event>> {
        val (placed, missing) = events.map { it to it.location?.let { location -> Places.pinIn(location) ?: coordinates(location) } }
            .partition { it.second != null }
        val places = placed.groupBy { (_, at) -> at!!.let { round(it.first * 10_000) to round(it.second * 10_000) } }
            .map { (_, group) ->
                val newest = group.map { it.first }.sortedWith(compareByDescending<Event> { it.date }.thenByDescending { it.updatedAt })
                val at = group.first().second!!
                val address = newest.first().location!!.trim()
                Place(at.first, at.second, address.substringBefore(',').trim(), address, newest)
            }
            .sortedWith(compareByDescending<Place> { it.events.size }.thenBy { it.title })
        return places to missing.map { it.first }.sortedByDescending { it.date }
    }
}
