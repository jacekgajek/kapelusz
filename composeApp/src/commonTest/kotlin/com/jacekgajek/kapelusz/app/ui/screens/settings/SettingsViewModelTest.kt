package com.jacekgajek.kapelusz.app.ui.screens.settings

import com.jacekgajek.kapelusz.model.Rejection
import com.jacekgajek.kapelusz.model.RejectionCode
import com.jacekgajek.kapelusz.ops.Operation
import com.jacekgajek.kapelusz.ops.RenameBand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The settings screen's state ([SettingsViewModel]): the dialog cell, refusals kept until shown, signing out once. */
class SettingsViewModelTest {
    private val refusal = Rejection(RejectionCode.FORBIDDEN, "no")
    private val sent = mutableListOf<Operation>()
    private var refuse = false
    private val screen = SettingsViewModel { op ->
        sent += op
        refusal.takeIf { refuse }
    }

    @Test
    fun aRefusedChangeIsKeptForTheScreenUntilShown() {
        screen.dispatch(RenameBand("Band"))
        assertNull(screen.refusal)
        refuse = true
        screen.dispatch(RenameBand("Other"))
        assertEquals(refusal, screen.refusal)
        assertEquals(2, sent.size, "every change reaches the band; the refusal is only told")
        screen.refusalShown()
        assertNull(screen.refusal)
    }

    @Test
    fun oneDialogAtATime() {
        screen.open(SettingsDialog.RENAMING)
        screen.open(SettingsDialog.LANGUAGE)
        assertEquals(SettingsDialog.LANGUAGE, screen.dialog)
        screen.dismiss()
        assertNull(screen.dialog)
    }

    @Test
    fun signingOutStartsOnceAndStaysBusy() {
        var started = 0
        screen.signOut { started++ }
        screen.signOut { started++ }
        assertTrue(screen.signingOut)
        assertEquals(1, started)
    }
}
