package com.jacekgajek.kapelusz.app.data

import com.jacekgajek.kapelusz.app.sync.ApiClient
import com.jacekgajek.kapelusz.domain.PlayedPlaces
import com.jacekgajek.kapelusz.model.Id
import com.jacekgajek.kapelusz.model.KapeluszJson
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.PairSerializer
import kotlinx.serialization.builtins.serializer

/** Where event locations are, as far as known: coordinates by [PlayedPlaces.key], those Google can't find, those still asked. */
data class LocatedPlaces(
    val coordinates: Map<String, Pair<Double, Double>> = emptyMap(),
    val notFound: Set<String> = emptySet(),
    val pending: Int = 0,
    /** The server couldn't be asked (e.g. offline): what is known is shown. */
    val failed: Boolean = false,
) {
    /** Still asking the server. */
    val finding: Boolean get() = !failed && pending != 0
}

/**
 * The coordinates of event locations for the "Where we played" map (ITERATION_7 §8), from the server's cache of
 * Google's answers; those found are kept on the device too, so the map opens at once next time.
 */
class PlacesLocator(private val store: KeyValueStore, private val api: ApiClient) {
    private val lock = Mutex()
    private var known: MutableMap<String, Pair<Double, Double>>? = null

    private suspend fun known(): MutableMap<String, Pair<Double, Double>> = lock.withLock {
        known ?: (store.read(KEY)?.let { runCatching { KapeluszJson.decodeFromString(SERIALIZER, it) }.getOrNull() }.orEmpty().toMutableMap()).also { known = it }
    }

    /** What is known at once, then the server's answers: again while some are still being looked up, a few times. */
    fun locate(bandId: Id): Flow<LocatedPlaces> = flow {
        emit(LocatedPlaces(known().toMap(), pending = -1))
        repeat(MAX_ROUNDS) { round ->
            val answer = runSuspendCatching { api.locations(bandId) }.getOrElse {
                emit(LocatedPlaces(known().toMap(), failed = true))
                return@flow
            }
            val found = answer.located.associate { PlayedPlaces.key(it.location) to (it.latitude to it.longitude) }
            lock.withLock {
                known!!.putAll(found)
                // A full storage only means asking again next time.
                runSuspendCatching { store.write(KEY, KapeluszJson.encodeToString(SERIALIZER, known!!)) }
            }
            val notFound = answer.notFound.map(PlayedPlaces::key).toSet()
            val last = round == MAX_ROUNDS - 1
            // After the last round, the rest is just not placed (yet): no endless "finding".
            emit(LocatedPlaces(known().toMap(), notFound, if (last) 0 else answer.pending.size))
            if (answer.pending.isEmpty()) return@flow
            if (!last) delay(ROUND_DELAY_MS)
        }
    }

    private companion object {
        const val KEY = "kapelusz-places"
        val SERIALIZER = MapSerializer(String.serializer(), PairSerializer(Double.serializer(), Double.serializer()))
        /** 25 new addresses a round: a band's history of a few hundred concerts is placed within a minute. */
        const val MAX_ROUNDS = 12
        const val ROUND_DELAY_MS = 1_500L
    }
}
