package com.jacekgajek.kapelusz.app.ui.screens.settings

import com.jacekgajek.kapelusz.model.Rejection
import com.jacekgajek.kapelusz.model.RejectionCode
import com.jacekgajek.kapelusz.ops.Operation
import com.jacekgajek.kapelusz.ops.RenameBand
import io.kotest.assertions.withClue
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/** The settings screen's state ([SettingsViewModel]): the dialog cell, refusals kept until shown, signing out once. */
class SettingsViewModelTest :
    ExpectSpec({
        isolationMode = IsolationMode.InstancePerRoot
        val refusal = Rejection(RejectionCode.FORBIDDEN, "no")
        val sent = mutableListOf<Operation>()
        var refuse = false
        val screen = SettingsViewModel { op ->
            sent += op
            refusal.takeIf { refuse }
        }

        expect("a refused change is kept for the screen until shown") {
            screen.dispatch(RenameBand("Band"))
            screen.refusal.shouldBeNull()
            refuse = true
            screen.dispatch(RenameBand("Other"))
            screen.refusal shouldBe refusal
            withClue("every change reaches the band; the refusal is only told") { sent.size shouldBe 2 }
            screen.refusalShown()
            screen.refusal.shouldBeNull()
        }

        expect("one dialog at a time") {
            screen.open(SettingsDialog.RENAMING)
            screen.open(SettingsDialog.LANGUAGE)
            screen.dialog shouldBe SettingsDialog.LANGUAGE
            screen.dismiss()
            screen.dialog.shouldBeNull()
        }

        expect("signing out starts once and stays busy") {
            var started = 0
            screen.signOut { started++ }
            screen.signOut { started++ }
            screen.signingOut shouldBe true
            started shouldBe 1
        }
    })
