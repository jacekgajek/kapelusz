package com.jacekgajek.kapelusz.app.ui

import com.jacekgajek.kapelusz.model.RejectionReason
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.collections.shouldBeEmpty
import java.io.File

/** Every refusal reason must have a text in each UI language. Runs as a JVM host test. */
class RejectionTextsTest :
    ExpectSpec({
        expect("every reason is translated") {
            listOf("values", "values-pl").forEach { folder ->
                val xml = File("src/commonMain/composeResources/$folder/strings.xml").readText()
                val missing = RejectionReason.entries.map { "reject_${it.name.lowercase()}" }.filter { "name=\"$it\"" !in xml }
                withClue("$folder is missing $missing") { missing.shouldBeEmpty() }
            }
        }
    })
