package com.jacekgajek.kapelusz.wire

import com.jacekgajek.kapelusz.api.HistoryEntry
import com.jacekgajek.kapelusz.history.KapeluszJson
import com.jacekgajek.kapelusz.history.OpEnvelope
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.ops.Operation
import kotlin.test.Test
import kotlinx.serialization.builtins.ListSerializer

/**
 * Prints the JSON of [WireSamples] as the body of `WireFixtures.kt`, for a deliberate change of the wire format:
 * `./gradlew :shared:jvmTest --tests '*WireFixtureDump*'`, then take the lines between `BEGIN` and `END` from
 * `shared/build/test-results/jvmTest/TEST-com.jacekgajek.kapelusz.wire.WireFixtureDump.xml`.
 */
class WireFixtureDump {
    @Test
    fun dump() {
        val quote = "\"\"\""
        val ops = WireSamples.everyOperation.joinToString(",\n") { op ->
            "        $quote${KapeluszJson.encodeToString(Operation.serializer(), op)}$quote"
        }
        val log = KapeluszJson.encodeToString(ListSerializer(OpEnvelope.serializer()), WireSamples.log)
        val band = KapeluszJson.encodeToString(Band.serializer(), WireSamples.storedBand())
        val entry = KapeluszJson.encodeToString(HistoryEntry.serializer(), WireSamples.historyEntry)
        println("BEGIN WIRE FIXTURES")
        println("    val OPERATIONS: List<String> = listOf(\n$ops,\n    )")
        println("    const val LOG = \"\"\"$log\"\"\"")
        println("    const val BAND = \"\"\"$band\"\"\"")
        println("    const val HISTORY_ENTRY = \"\"\"$entry\"\"\"")
        println("END WIRE FIXTURES")
    }
}
