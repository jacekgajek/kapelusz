package com.jacekgajek.kapelusz.app.ui.screens.settings

import androidx.compose.runtime.Composable
import com.jacekgajek.kapelusz.api.CalendarSubscription
import com.jacekgajek.kapelusz.api.CalendarSubscriptionChange
import com.jacekgajek.kapelusz.app.ui.components.PaymentKind
import com.jacekgajek.kapelusz.model.AgendaTemplateItem
import com.jacekgajek.kapelusz.model.FundCut

/**
 * Everything Settings shows and changes, by section (built by [settingsFor]). An action is null where the user may
 * not take it: the screen then shows the setting read-only, or not at all.
 */
data class Settings(
    val band: BandSettings,
    val fund: BandFundSettings,
    val personal: PersonalSettings,
    /** The band's agenda template; null for non-leaders. */
    val agenda: AgendaSettings?,
    /** No band fund and no whole-band calendar for artists. */
    val artist: Boolean,
)

/** The band itself: its name, its members, leaving or deleting it. */
class BandSettings(
    val name: String,
    val onRename: ((String) -> Unit)?,
    /** Members not archived, said on the Band members row. */
    val memberCount: Int,
    /** Members with an account other than the user, said when deleting the band. */
    val otherMembers: Int,
    /** Leaves the band (not as its owner, who has [onDelete]). Throws when it did not work (e.g. offline). */
    val onLeave: (suspend () -> Unit)?,
    /** What else leaving ends, said in its dialog (a pair settling together, the band fund held). */
    val leaveWarnings: List<String>,
    /** Deletes the band for everyone; only for its owner. Throws when it did not work (e.g. offline). */
    val onDelete: (suspend () -> Unit)?,
    /** While the band is to be deleted: its notice, in place of Delete band. */
    val deletionNotice: (@Composable () -> Unit)?,
    /** The band's raw file from the server, for the secret menu; null for non-leaders. */
    val rawData: (suspend () -> String)?,
)

/** The band fund page: whether it is on, who holds it, its default for new events and where to pay into it. */
class BandFundSettings(
    /** The holder's member id; null when the fund is off. */
    val treasurer: String?,
    /** Who holds the band fund (also if they are no longer among [treasurerOptions]); null when it is off. */
    val treasurerName: String?,
    /** Who may hold it: member id and name. */
    val treasurerOptions: List<Pair<String, String>>,
    val onTreasurer: ((String) -> Unit)?,
    /** Turns the band fund off; null while it holds or owes money ([balance] says how much it holds). */
    val onDisable: (() -> Unit)?,
    val balance: String,
    /** The fund's default for new events; [defaultSetting] is null for non-leaders. */
    val default: FundCut?,
    val defaultSetting: FundDefaultSetting?,
    val currency: String,
    /** Where to pay into the band fund; [onPayment] for its holder and leads. */
    val blikPhone: String?,
    val bankAccount: String?,
    val onPayment: ((PaymentKind, String?) -> Unit)?,
)

/** The user's own: language, calendar, payment details and account. */
class PersonalSettings(
    /** The language chosen in the app; null follows the system. */
    val language: String?,
    val onLanguage: (String?) -> Unit,
    /** The calendar subscription (needs the server): get it, or change it. */
    val calendar: suspend (CalendarSubscriptionChange?) -> CalendarSubscription,
    /** The user's BLIK number in this band, as stored; [onBlikPhone] is null for whoever is never paid. */
    val blikPhone: String?,
    val onBlikPhone: ((String?) -> Unit)?,
    /** The user's bank account in this band, as [blikPhone]. */
    val bankAccount: String?,
    val onBankAccount: ((String?) -> Unit)?,
    /** The account's e-mail address (or name). */
    val account: String?,
    /** The user's Google photo (as their member in this band has it). */
    val picture: String?,
    val onSignOut: () -> Unit,
    /** Signs out every device of the user, this one too. Throws when it did not work (e.g. offline). */
    val onSignOutEverywhere: suspend () -> Unit,
)

/** The agenda template of new events (leaders only). */
class AgendaSettings(
    val template: List<AgendaTemplateItem>,
    /** The band stored its own template (even if it equals the defaults). */
    val stored: Boolean,
    val onChange: (List<AgendaTemplateItem>) -> Unit,
)
