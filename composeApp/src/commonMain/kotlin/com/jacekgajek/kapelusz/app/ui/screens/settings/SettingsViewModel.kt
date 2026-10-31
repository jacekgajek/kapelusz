package com.jacekgajek.kapelusz.app.ui.screens.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.jacekgajek.kapelusz.model.Rejection
import com.jacekgajek.kapelusz.ops.Operation

/** What is open over the settings: one at a time. */
enum class SettingsDialog {
    LANGUAGE,
    RENAMING,
    BLIK,
    ACCOUNT,
    SIGN_OUT_EVERYWHERE,
    LEAVING,
    DELETING,
    SECRET_MENU,

    /** The fund page: the question before turning the fund off. */
    FUND_DISABLING,

    /** The fund page's default cut editor. */
    FUND_DEFAULT,
}

/**
 * The settings screen's state (REFACTORING.md 2.8): the dialog open, the sign-out under way, and the refusals of
 * the changes made here, which the screen shows (they used to be dropped: each action is offered only where it is
 * allowed, but the band may have changed meanwhile). Scoped to the screen's back-stack entry.
 */
class SettingsViewModel(private val dispatchOp: (Operation) -> Rejection?) : ViewModel() {
    var dialog by mutableStateOf<SettingsDialog?>(null)

    /** Signing out takes a moment (sync stops, data is cleared): a spinner at once, and no second tap. */
    var signingOut by mutableStateOf(false)
        private set

    /** The last change the band refused, until the screen has shown it. */
    var refusal by mutableStateOf<Rejection?>(null)
        private set

    fun open(which: SettingsDialog) {
        dialog = which
    }

    fun dismiss() {
        dialog = null
    }

    /** Runs a change made in the settings; a refusal is kept for the screen to show. */
    fun dispatch(op: Operation) {
        dispatchOp(op)?.let { refusal = it }
    }

    fun refusalShown() {
        refusal = null
    }

    fun signOut(signOutInBackground: () -> Unit) {
        if (signingOut) return
        signingOut = true
        signOutInBackground()
    }
}
