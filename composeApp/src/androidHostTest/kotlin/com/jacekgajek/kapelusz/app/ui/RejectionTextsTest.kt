package com.jacekgajek.kapelusz.app.ui

import com.jacekgajek.kapelusz.ops.RejectionReason
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Every refusal reason must have a text in each UI language. Runs as a JVM host test. */
class RejectionTextsTest {
    @Test
    fun everyReasonIsTranslated() {
        listOf("values", "values-pl").forEach { folder ->
            val xml = File("src/commonMain/composeResources/$folder/strings.xml").readText()
            val missing = RejectionReason.entries.map { "reject_${it.name.lowercase()}" }.filter { "name=\"$it\"" !in xml }
            assertTrue(missing.isEmpty(), "$folder is missing $missing")
        }
    }
}
