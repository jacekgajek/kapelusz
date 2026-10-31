package com.jacekgajek.kapelusz.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.band_hint_hide
import com.jacekgajek.kapelusz.app.generated.band_hint_no_app
import com.jacekgajek.kapelusz.app.generated.band_hint_no_members
import com.jacekgajek.kapelusz.app.generated.band_hint_open_roster
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Member
import com.jacekgajek.kapelusz.model.Role
import org.jetbrains.compose.resources.stringResource

/** What a band still lacks, said to its leaders on the events list. */
enum class BandHint {
    /** Nobody but [me] on the roster: no one to play or settle with. Can't be hidden. */
    NO_MEMBERS,

    /** Others on the roster, but none of them uses the app. Hidden on request: some bands are kept by one person. */
    NO_APP,
}

/** The hint for [me] (owner and leaders only) in [band], unless hidden on this device ([hiddenNoApp]). */
fun bandHint(band: Band, me: Member?, hiddenNoApp: Boolean): BandHint? {
    if (me == null || !me.role.atLeast(Role.LEAD)) return null
    val others = band.members.filter { !it.archived && it.id != me.id }
    return when {
        others.isEmpty() -> BandHint.NO_MEMBERS
        others.none { it.userId != null } && !hiddenNoApp -> BandHint.NO_APP
        else -> null
    }
}

/** The hint as a card on top of the events list, like an invitation to settle together: the roster a tap away. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BandHintCard(hint: BandHint, onRoster: () -> Unit, onHide: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(if (hint == BandHint.NO_MEMBERS) Res.string.band_hint_no_members else Res.string.band_hint_no_app))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onRoster) { Text(stringResource(Res.string.band_hint_open_roster)) }
                if (hint == BandHint.NO_APP) OutlinedButton(onClick = onHide) { Text(stringResource(Res.string.band_hint_hide)) }
            }
        }
    }
}
