package com.jacekgajek.kapelusz.app.e2e

import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.pdf_export
import com.jacekgajek.kapelusz.app.generated.pdf_export_failed
import com.jacekgajek.kapelusz.app.generated.pdf_generate
import com.jacekgajek.kapelusz.app.generated.settings
import com.jacekgajek.kapelusz.app.generated.summary_title
import io.kotest.core.spec.style.ExpectSpec

/** The summary of a period, and its PDF. */
class ExportTest :
    ExpectSpec({
        e2e("the summary exports a PDF of the period") {
            signIn(freshUser("Alice"))
            createBand("Night Owls", "Alice")
            createEvent("Jazz Night")
            tap("Alice")
            receiveMoney("1200")
            tap("Back")

            tap(Res.string.settings)
            pick(Res.string.summary_title)
            see("1,200.00")
            tap(Res.string.pdf_export)
            tap(Res.string.pdf_generate)
            missing(Res.string.pdf_export_failed)
        }
    })
