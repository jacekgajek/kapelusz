package com.jacekgajek.kapelusz.app.ui.screens

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.serialization.saved
import androidx.lifecycle.viewModelScope
import com.jacekgajek.kapelusz.app.data.BandState
import com.jacekgajek.kapelusz.app.data.newId
import com.jacekgajek.kapelusz.app.platform.PreparedFile
import com.jacekgajek.kapelusz.app.ui.CostRoute
import com.jacekgajek.kapelusz.app.ui.components.parseMoney
import com.jacekgajek.kapelusz.model.AttachmentOwner
import com.jacekgajek.kapelusz.model.Cost
import com.jacekgajek.kapelusz.model.CostCategory
import com.jacekgajek.kapelusz.model.Event
import com.jacekgajek.kapelusz.model.Member
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.Rejection
import com.jacekgajek.kapelusz.ops.AddCost
import com.jacekgajek.kapelusz.ops.Change
import com.jacekgajek.kapelusz.ops.DeleteCost
import com.jacekgajek.kapelusz.ops.Operation
import com.jacekgajek.kapelusz.ops.UpdateCost
import com.jacekgajek.kapelusz.rules.Capabilities
import com.jacekgajek.kapelusz.view.CostChoices
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/** What the user has typed into the cost form; kept across rotation and the camera app's round trip (ITERATION_3 R8). */
@Serializable
data class CostDraft(
    val amountText: String,
    val payerId: String?,
    val date: LocalDate,
    val eventId: String?,
    val category: CostCategory?,
    val description: String,
)

/** The form as the screen draws it. */
data class CostUiState(
    val isNew: Boolean,
    val notFound: Boolean,
    val editable: Boolean,
    /** Who may have paid it (several: a picker; one: shown). */
    val payers: List<Member>,
    /** The events it may be for, `null` first for none; empty when the event is fixed. */
    val events: List<String?>,
    val forEvent: Event?,
    val canSave: Boolean,
    val canDelete: Boolean,
    val attachmentsEditable: Boolean,
    val attachmentsRemovable: Boolean,
)

enum class CostDialog { CONFIRM_DELETE }

/** The cost as it was when the form opened (none for a new one); a holder, as the saved state takes no null. */
@Serializable
private data class Opened(val cost: Cost?)

/** What happened on Save or Delete: where the navigator goes, or why the band refused. */
sealed interface CostOutcome {
    data class Added(val costId: String, val forOther: Boolean) : CostOutcome
    data object Updated : CostOutcome
    data object Deleted : CostOutcome
    data class Refused(val rejection: Rejection) : CostOutcome
}

/**
 * The cost form (ITERATION_1 §5): a new cost, or an existing one edited. Save sends only what the user changed since
 * the form opened ([opened]), so a change someone else made meanwhile is not overwritten (ITERATION_3 R8). Lives as
 * long as the screen is on the back stack, following the band as it changes ([bands]).
 */
class CostViewModel(
    private val route: CostRoute,
    bands: StateFlow<BandState>,
    private val dispatch: (Operation) -> Rejection?,
    private val attachAfterSave: (costId: String, List<PreparedFile>) -> Unit,
    sharedFiles: List<PreparedFile>,
    handle: SavedStateHandle,
    today: LocalDate,
    /** An existing cost's amount as typed text, in the app's language. */
    amountText: (Money) -> String,
) : ViewModel() {
    private var ready by mutableStateOf(bands.value as? BandState.Ready)

    init {
        viewModelScope.launch { bands.collect { ready = it as? BandState.Ready } }
    }

    private val band get() = ready?.band
    val existing: Cost? get() = route.costId?.let { band?.cost(it) }

    /** The cost as it was when the form opened: read at once, as the delegate would otherwise take it on first use. */
    private val opened: Opened by handle.saved(Opened.serializer()) { Opened(existing) }

    init {
        opened
    }

    /** Opened from an event: the new cost is for that event (fixed), so only its participants can have paid it. */
    val forEvent: Event? get() = route.forEventId?.takeIf { existing == null }?.let { band?.event(it) }

    /** [draft] as the saved state keeps it for after process death; a plain field, so [draft] mirrors it for Compose. */
    private var savedDraft: CostDraft by handle.saved(CostDraft.serializer()) {
        val cost = existing
        val event = forEvent
        val me = ready?.me?.id
        CostDraft(
            amountText = cost?.let { amountText(it.amount) } ?: "",
            payerId = cost?.memberId ?: me?.takeIf { event == null || event.participant(it) != null },
            date = cost?.date ?: today,
            eventId = cost?.eventId ?: route.forEventId?.takeIf { band?.event(it) != null },
            category = cost?.category,
            description = cost?.description.orEmpty(),
        )
    }

    private val draftState = mutableStateOf(savedDraft)

    /** What the user has typed. */
    var draft: CostDraft
        get() = draftState.value
        private set(value) {
            draftState.value = value
            savedDraft = value
        }

    var dialog by mutableStateOf<CostDialog?>(null)
        private set

    /** Photos or PDFs shared to the app for a new cost of this event (ITERATION_6 §1). */
    var drafts by mutableStateOf(sharedFiles)

    /** Once only: the screen stays tappable while it fades out, and a second tap would add the cost again. */
    private var saved by mutableStateOf(false)

    val state: CostUiState by derivedStateOf {
        val state = ready
        val cost = existing
        val can = state?.let { Capabilities(it.band, it.userId) }
        val choices = state?.let { CostChoices(it.view, can!!) }
        val notFound = route.costId != null && cost == null
        val editable = cost == null || can?.editCost(cost) == true
        val amount = parseMoney(draft.amountText)?.takeIf { it.isPositive }
        val owner = cost?.let { AttachmentOwner.OfCost(it.id) }
        CostUiState(
            isNew = cost == null,
            notFound = notFound,
            editable = editable,
            payers = choices?.payers(forEvent).orEmpty(),
            events = if (forEvent != null) emptyList() else choices?.events(cost, draft.payerId, draft.eventId).orEmpty(),
            forEvent = forEvent,
            canSave = state != null && !notFound && editable && amount != null && draft.payerId != null && !saved,
            canDelete = cost != null && can?.deleteCost(cost) == true,
            attachmentsEditable = owner == null || can?.attachTo(owner) == true,
            attachmentsRemovable = owner == null || can?.detachFrom(owner) == true,
        )
    }

    fun amount(text: String) = edit { copy(amountText = text) }

    fun date(date: LocalDate) = edit { copy(date = date) }

    fun category(category: CostCategory?) = edit { copy(category = if (this.category == category) null else category) }

    fun description(text: String) = edit { copy(description = text) }

    fun event(eventId: String?) = edit { copy(eventId = eventId) }

    /** Another payer: an event they are not in is no longer a choice. */
    fun payer(memberId: String?) = edit {
        val events = if (forEvent != null) listOf(eventId) else choices()?.events(existing, memberId, eventId).orEmpty()
        copy(payerId = memberId, eventId = eventId.takeIf { it in events })
    }

    private fun choices(): CostChoices? = ready?.let { CostChoices(it.view, Capabilities(it.band, it.userId)) }

    /** An event no longer among the choices (another payer, archived meanwhile) is dropped. */
    fun pruneEvent() {
        if (forEvent == null && draft.eventId !in state.events) draft = draft.copy(eventId = null)
    }

    /** A single possible payer (always the case for members) is taken without asking; called once the form shows. */
    fun takeOnlyPayer() {
        if (existing == null && draft.payerId == null) state.payers.singleOrNull()?.let { draft = draft.copy(payerId = it.id) }
    }

    fun askDelete() {
        dialog = CostDialog.CONFIRM_DELETE
    }

    fun dismissDialog() {
        dialog = null
    }

    fun save(): CostOutcome? {
        if (!state.canSave) return null
        val amount = parseMoney(draft.amountText) ?: return null
        val cost = existing
        val outcome = if (cost == null) addNew(amount) else update(cost, amount)
        if (outcome !is CostOutcome.Refused) saved = true
        return outcome
    }

    fun delete(): CostOutcome? {
        val cost = existing ?: return null
        dialog = null
        return dispatch(DeleteCost(cost.id))?.let { CostOutcome.Refused(it) } ?: CostOutcome.Deleted
    }

    private fun addNew(amount: Money): CostOutcome {
        val id = newId()
        val payer = draft.payerId!!
        val op = AddCost(id, payer, amount, draft.date, draft.eventId, draft.category, draft.description.ifBlank { null })
        dispatch(op)?.let { return CostOutcome.Refused(it) }
        attachAfterSave(id, drafts)
        return CostOutcome.Added(id, forOther = payer != ready?.me?.id)
    }

    private fun update(cost: Cost, amount: Money): CostOutcome {
        val before = opened.cost ?: cost
        val description = draft.description.ifBlank { null }
        val op = UpdateCost(
            cost.id,
            amount = Change(amount).takeIf { amount != before.amount },
            date = Change(draft.date).takeIf { draft.date != before.date },
            incurredForEventId = Change(draft.eventId).takeIf { draft.eventId != before.eventId },
            category = Change(draft.category).takeIf { draft.category != before.category },
            description = Change(description).takeIf { draft.description.trim() != before.description.orEmpty() },
        )
        return dispatch(op)?.let { CostOutcome.Refused(it) } ?: CostOutcome.Updated
    }

    private inline fun edit(change: CostDraft.() -> CostDraft) {
        if (state.editable) draft = draft.change()
    }
}
