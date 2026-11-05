package com.jacekgajek.kapelusz.app.ui.screens

import com.jacekgajek.kapelusz.model.Member
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.datetime.LocalDate

/** The summary screen's dialog cell and the PDF export form. */
class SummaryViewModelTest :
    ExpectSpec({
        expect("one dialog at a time") {
            val model = SummaryViewModel()
            val member = Member(id = "m1", name = "Alice")
            model.dialog.shouldBeNull()

            model.chooseRange()
            model.dialog.shouldBeInstanceOf<SummaryDialog.ChoosingRange>()

            model.invite(member)
            model.dialog.shouldBeInstanceOf<SummaryDialog.Inviting>().member shouldBe member

            model.export()
            model.dialog.shouldBeInstanceOf<SummaryDialog.Exporting>()

            model.dismiss()
            model.dialog.shouldBeNull()
        }

        expect("the export's choices are kept between openings") {
            val model = SummaryViewModel()
            model.export()
            val form = model.dialog.shouldBeInstanceOf<SummaryDialog.Exporting>().form
            form.events = false
            form.eventsOnSeparatePages = false

            model.dismiss()
            model.export()
            val reopened = model.dialog.shouldBeInstanceOf<SummaryDialog.Exporting>().form
            reopened shouldBeSameInstanceAs form
            reopened.events shouldBe false
            reopened.eventsOnSeparatePages shouldBe false
        }

        expect("the export needs the events or the fund") {
            val form = PdfExportForm()
            form.valid shouldBe true

            form.events = false
            form.valid shouldBe true

            form.bandFund = false
            form.valid shouldBe false

            form.events = true
            form.eventsOnSeparatePages = false
            val options = form.options(LocalDate(2026, 1, 1), LocalDate(2026, 1, 31))
            options.events shouldBe true
            options.bandFund shouldBe false
            options.eventsOnSeparatePages shouldBe false
            options.from shouldBe LocalDate(2026, 1, 1)
            options.to shouldBe LocalDate(2026, 1, 31)
        }
    })
