package com.jacekgajek.kapelusz.app.ui.screens.event

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.band_fund
import com.jacekgajek.kapelusz.app.generated.share_awaited
import com.jacekgajek.kapelusz.app.generated.share_settlement
import com.jacekgajek.kapelusz.app.generated.share_settlement_copied
import com.jacekgajek.kapelusz.app.generated.share_settlement_failed
import com.jacekgajek.kapelusz.app.generated.share_text_all_settled
import com.jacekgajek.kapelusz.app.generated.share_text_awaiting_rest
import com.jacekgajek.kapelusz.app.generated.share_text_incomplete
import com.jacekgajek.kapelusz.app.generated.share_text_done
import com.jacekgajek.kapelusz.app.generated.share_text_footer
import com.jacekgajek.kapelusz.app.generated.share_text_from_fund
import com.jacekgajek.kapelusz.app.generated.share_text_income
import com.jacekgajek.kapelusz.app.generated.share_text_refunds
import com.jacekgajek.kapelusz.app.generated.share_text_fixed
import com.jacekgajek.kapelusz.app.generated.share_text_share_with_refunds
import com.jacekgajek.kapelusz.app.generated.share_text_shares
import com.jacekgajek.kapelusz.app.generated.share_text_still_to_pay
import com.jacekgajek.kapelusz.app.generated.share_text_to_fund
import com.jacekgajek.kapelusz.app.platform.ShareResult
import com.jacekgajek.kapelusz.app.platform.rememberShare
import com.jacekgajek.kapelusz.app.ui.components.language
import com.jacekgajek.kapelusz.app.ui.model.SettlementWords
import com.jacekgajek.kapelusz.app.ui.model.settlementText
import com.jacekgajek.kapelusz.domain.EventStatus
import com.jacekgajek.kapelusz.domain.Settlement
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Event
import com.jacekgajek.kapelusz.app.generated.pair_names
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** The words of the shared text in the app's language; read without arguments, so the "%1$s" stay for [settlementText]. */
@Composable
private fun settlementWords() = SettlementWords(
    fundLabel = stringResource(Res.string.band_fund),
    income = stringResource(Res.string.share_text_income),
    refunds = stringResource(Res.string.share_text_refunds),
    toFund = stringResource(Res.string.share_text_to_fund),
    fromFund = stringResource(Res.string.share_text_from_fund),
    shares = stringResource(Res.string.share_text_shares),
    shareWithRefunds = stringResource(Res.string.share_text_share_with_refunds),
    fixed = stringResource(Res.string.share_text_fixed),
    done = stringResource(Res.string.share_text_done),
    stillToPay = stringResource(Res.string.share_text_still_to_pay),
    allSettled = stringResource(Res.string.share_text_all_settled),
    incomplete = stringResource(Res.string.share_text_incomplete),
    footer = stringResource(Res.string.share_text_footer),
    awaited = stringResource(Res.string.share_awaited),
    awaitingRest = stringResource(Res.string.share_text_awaiting_rest),
    pair = stringResource(Res.string.pair_names),
)

/**
 * Shares the settlement as text (ITERATION_3 §2.1): the share sheet on Android and where the browser has Web Share,
 * otherwise the clipboard, said in the snackbar. Null while there is nothing to share (no event, or a draft).
 */
@Composable
internal fun rememberShareSettlement(band: Band, event: Event?, settlement: Settlement?, snackbar: SnackbarHostState): (() -> Unit)? {
    val share = rememberShare()
    val scope = rememberCoroutineScope()
    val words = settlementWords()
    val lang = language
    val copied = stringResource(Res.string.share_settlement_copied)
    val failed = stringResource(Res.string.share_settlement_failed)
    if (event == null || settlement == null || settlement.status == EventStatus.DRAFT) return null
    return {
        val text = settlementText(band, event, settlement, lang, words)
        scope.launch {
            when (share(text)) {
                ShareResult.COPIED -> snackbar.showSnackbar(copied)
                ShareResult.FAILED -> snackbar.showSnackbar(failed)
                ShareResult.SHARED -> {}
            }
        }
    }
}

/** "Share settlement" under the Settlement panel, for everyone in the band. */
@Composable
internal fun ShareSettlementButton(onShare: () -> Unit) {
    OutlinedButton(onClick = onShare, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Icon(Icons.Default.Share, null)
        Text(stringResource(Res.string.share_settlement), Modifier.padding(start = 8.dp))
    }
}
