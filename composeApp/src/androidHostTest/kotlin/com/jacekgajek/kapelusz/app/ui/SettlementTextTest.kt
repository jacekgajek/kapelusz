package com.jacekgajek.kapelusz.app.ui

import com.jacekgajek.kapelusz.app.ui.model.SettlementWords
import com.jacekgajek.kapelusz.app.ui.model.settlementText
import com.jacekgajek.kapelusz.auth.Actor
import com.jacekgajek.kapelusz.domain.SettlementCalculator
import com.jacekgajek.kapelusz.model.BAND_FUND
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.IncomeSource
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.Participant
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AddCost
import com.jacekgajek.kapelusz.ops.AddHolding
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.ApplyResult
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.ops.Engine
import com.jacekgajek.kapelusz.ops.LinkUser
import com.jacekgajek.kapelusz.ops.Operation
import com.jacekgajek.kapelusz.ops.RecordTransfer
import com.jacekgajek.kapelusz.ops.SetFundAmount
import com.jacekgajek.kapelusz.ops.SetRefund
import com.jacekgajek.kapelusz.ops.SetTreasurer
import com.jacekgajek.kapelusz.ops.SettleSuggestions
import kotlinx.datetime.LocalDate
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The shared settlement text (ITERATION_3 §2.1) with the real words of each UI language, read from the string
 * resources as the app does. Runs as a JVM host test.
 */
class SettlementTextTest {
    private var opCount = 0

    private fun Band.apply(vararg ops: Operation): Band = ops.fold(this) { band, op ->
        (Engine.apply(band, Actor.User("u-bob"), op, "op${opCount++}", 1) as ApplyResult.Applied).band
    }

    /** The words as `stringResource` gives them without arguments: the raw texts of the language's folder. */
    private fun words(folder: String): SettlementWords {
        val strings = listOf("strings.xml", "strings_share.xml").flatMap { file ->
            Regex("""<string name="([^"]+)">(.*?)</string>""").findAll(File("src/commonMain/composeResources/$folder/$file").readText())
                .map { it.groupValues[1] to it.groupValues[2] }.toList()
        }.toMap()
        return SettlementWords(
            fundLabel = strings.getValue("band_fund"),
            income = strings.getValue("share_text_income"),
            refunds = strings.getValue("share_text_refunds"),
            toFund = strings.getValue("share_text_to_fund"),
            fromFund = strings.getValue("share_text_from_fund"),
            shares = strings.getValue("share_text_shares"),
            shareWithRefunds = strings.getValue("share_text_share_with_refunds"),
            fixed = strings.getValue("share_text_fixed"),
            done = strings.getValue("share_text_done"),
            stillToPay = strings.getValue("share_text_still_to_pay"),
            allSettled = strings.getValue("share_text_all_settled"),
            footer = strings.getValue("share_text_footer"),
        )
    }

    // Bob collected 1 200; Alice holds the band fund and gets 150 back for a cost; 100 goes to the fund.
    private val gig = Band.create("b", "Band", "PLN", "alice", "Alice", "u-alice")
        .let { (Engine.apply(it, Actor.System, AddMember("bob", "Bob"), "m", 1) as ApplyResult.Applied).band }
        .let { (Engine.apply(it, Actor.System, LinkUser("bob", "u-bob", Role.ADMIN), "l", 1) as ApplyResult.Applied).band }
        .apply(
            SetTreasurer("alice"),
            CreateEvent("gig", "Club gig", LocalDate(2026, 9, 12), participants = listOf(Participant("alice"), Participant("bob"))),
            AddHolding("gig", "h", "bob", Money(120000), IncomeSource.DOOR),
            AddCost("c", "alice", Money(15000), LocalDate(2026, 9, 12), incurredForEventId = "gig"),
            SetRefund("gig", "c", Money(15000)),
            SetFundAmount("gig", Money(10000)),
            RecordTransfer("gig", "t", "bob", BAND_FUND, Money(5000)),
        )

    // Money keeps its no-break spaces in the text; plain spaces here, for readable expectations.
    private fun text(band: Band, language: String) =
        settlementText(band, band.event("gig")!!, SettlementCalculator.settle(band, band.event("gig")!!), language, words(if (language == "pl") "values-pl" else "values"))
            .replace('\u00A0', ' ')

    @Test
    fun anOpenEventWithTheBandFundInEnglish() {
        assertEquals(
            """
            Club gig · Sat 12 Sep
            Income 1,200.00 PLN · costs refunded 150.00 PLN · band fund 100.00 PLN
            Shares: Alice 475.00 PLN (+ 150.00 PLN refunds), Bob 475.00 PLN
            Done:
            Bob → Band fund (Alice): 50.00 PLN
            Still to pay:
            Bob → Alice: 625.00 PLN
            Bob → Band fund (Alice): 50.00 PLN
            Pass the Hat · kapelusz.jacekgajek.com
            """.trimIndent(),
            text(gig, "en"),
        )
    }

    @Test
    fun anOpenEventWithTheBandFundInPolish() {
        assertEquals(
            """
            Club gig · sob. 12.09
            Przychód 1 200,00 zł · zwroty kosztów 150,00 zł · do kasy zespołowej 100,00 zł
            Udziały: Alice 475,00 zł (+ zwroty 150,00 zł), Bob 475,00 zł
            Przekazane:
            Bob → Kasa zespołowa (Alice): 50,00 zł
            Do przekazania:
            Bob → Alice: 625,00 zł
            Bob → Kasa zespołowa (Alice): 50,00 zł
            Kapelusz · kapelusz.jacekgajek.com
            """.trimIndent(),
            text(gig, "pl"),
        )
    }

    @Test
    fun aSettledEventInBothLanguages() {
        val settled = gig.apply(SettleSuggestions("gig"))
        assertEquals(
            """
            Done:
            Bob → Band fund (Alice): 50.00 PLN
            Bob → Alice: 625.00 PLN
            Bob → Band fund (Alice): 50.00 PLN
            All settled
            Pass the Hat · kapelusz.jacekgajek.com
            """.trimIndent(),
            text(settled, "en").lines().drop(3).joinToString("\n"),
        )
        assertEquals(
            """
            Przekazane:
            Bob → Kasa zespołowa (Alice): 50,00 zł
            Bob → Alice: 625,00 zł
            Bob → Kasa zespołowa (Alice): 50,00 zł
            Wszystko rozliczone
            Kapelusz · kapelusz.jacekgajek.com
            """.trimIndent(),
            text(settled, "pl").lines().drop(3).joinToString("\n"),
        )
    }

    @Test
    fun moneyFromTheBandFundAndNoRefunds() {
        val band = Band.create("b", "Band", "PLN", "alice", "Alice", "u-alice")
            .let { (Engine.apply(it, Actor.System, AddMember("bob", "Bob"), "m", 1) as ApplyResult.Applied).band }
            .let { (Engine.apply(it, Actor.System, LinkUser("bob", "u-bob", Role.ADMIN), "l", 1) as ApplyResult.Applied).band }
            .apply(
                SetTreasurer("alice"),
                CreateEvent("gig", "Wedding", LocalDate(2026, 9, 12), participants = listOf(Participant("alice"), Participant("bob"))),
                AddHolding("gig", "h", "bob", Money(10000), IncomeSource.DOOR),
                SetFundAmount("gig", Money(-2000)),
            )
        assertEquals(
            listOf("Income 100.00 PLN · from the band fund 20.00 PLN", "Shares: Alice 60.00 PLN, Bob 60.00 PLN"),
            text(band, "en").lines().subList(1, 3),
        )
    }

    /** ITERATION_3 §2.4: a fixed share says so, in both languages. */
    @Test
    fun aFixedShareSaysSo() {
        val band = gig.apply(com.jacekgajek.kapelusz.ops.SetFixedShare("gig", "bob", Money(20000)))
        assertTrue("Bob 200.00 PLN (fixed)" in text(band, "en"), text(band, "en"))
        assertTrue("Alice 750,00 zł (stała kwota)" !in text(band, "pl") && "Bob 200,00 zł (stała kwota)" in text(band, "pl"), text(band, "pl"))
    }
}
