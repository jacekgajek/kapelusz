package com.jacekgajek.kapelusz.app.ui

import com.jacekgajek.kapelusz.app.ui.model.SettlementWords
import com.jacekgajek.kapelusz.app.ui.model.settlementText
import com.jacekgajek.kapelusz.domain.SettlementCalculator
import com.jacekgajek.kapelusz.model.BAND_FUND
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.IncomeSource
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.ORGANISER
import com.jacekgajek.kapelusz.model.Participant
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AcceptSettleTogether
import com.jacekgajek.kapelusz.ops.AddCost
import com.jacekgajek.kapelusz.ops.AddHolding
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.ApplyResult
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.ops.Engine
import com.jacekgajek.kapelusz.ops.InviteToSettleTogether
import com.jacekgajek.kapelusz.ops.LinkUser
import com.jacekgajek.kapelusz.ops.Operation
import com.jacekgajek.kapelusz.ops.RecordTransfer
import com.jacekgajek.kapelusz.ops.SetFundAmount
import com.jacekgajek.kapelusz.ops.SetRefund
import com.jacekgajek.kapelusz.ops.SetTreasurer
import com.jacekgajek.kapelusz.ops.SetWeight
import com.jacekgajek.kapelusz.ops.SettleSuggestions
import com.jacekgajek.kapelusz.rules.Actor
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate

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
        val strings = listOf("strings.xml", "strings_share.xml", "strings_shares.xml").flatMap { file ->
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
            incomplete = strings.getValue("share_text_incomplete"),
            // The app passes the event's link; any text does for the layout.
            footer = "kapelusz.jacekgajek.com",
            awaited = strings.getValue("share_awaited"),
            awaitingRest = strings.getValue("share_text_awaiting_rest"),
            percent = strings.getValue("share_text_percent"),
            pair = strings.getValue("pair_names"),
        )
    }

    // Bob collected 1 200; Alice holds the band fund and gets 150 back for a cost; 100 goes to the fund.
    private val gig = Band.create("b", "Band", "PLN", "alice", "Alice", "u-alice")
        .let { (Engine.apply(it, Actor.System, AddMember("bob", "Bob"), "m", 1) as ApplyResult.Applied).band }
        .let { (Engine.apply(it, Actor.System, LinkUser("bob", "u-bob", Role.LEAD), "l", 1) as ApplyResult.Applied).band }
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
    private fun text(band: Band, language: String) = settlementText(band, band.event("gig")!!, SettlementCalculator.settle(band, band.event("gig")!!), language, words(if (language == "pl") "values-pl" else "values"))
        .replace('\u00A0', ' ')

    @Test
    fun anOpenEventWithTheBandFundInEnglish() {
        assertEquals(
            """
            Club gig · Sat 12 Sep

            Income 1,200.00 PLN
            costs refunded 150.00 PLN
            band fund 100.00 PLN

            Shares:
            Alice 475.00 PLN (+ 150.00 PLN refunds),
            Bob 475.00 PLN

            Done:
            Bob → Band fund (Alice): 50.00 PLN

            Still to pay:
            Bob → Alice: 625.00 PLN
            Bob → Band fund (Alice): 50.00 PLN

            kapelusz.jacekgajek.com
            """.trimIndent(),
            text(gig, "en"),
        )
    }

    @Test
    fun aPairSettlingTogetherIsNamedTogether() {
        // Bob and Carol settle together (ITERATION_5 §7); Bob collected 300, a third each.
        val band = gig
            .let { (Engine.apply(it, Actor.System, AddMember("carol", "Carol"), "m2", 1) as ApplyResult.Applied).band }
            .let { (Engine.apply(it, Actor.System, LinkUser("carol", "u-carol", Role.MEMBER), "l2", 1) as ApplyResult.Applied).band }
            .let { (Engine.apply(it, Actor.System, InviteToSettleTogether("bob", "carol"), "i", 1) as ApplyResult.Applied).band }
            .let { (Engine.apply(it, Actor.System, AcceptSettleTogether("bob", "carol"), "a", 1) as ApplyResult.Applied).band }
            .apply(
                CreateEvent("pair-gig", "Pair gig", LocalDate(2026, 9, 12), participants = listOf(Participant("alice"), Participant("bob"), Participant("carol"))),
                AddHolding("pair-gig", "h2", "bob", Money(30000), IncomeSource.DOOR),
            )
        val text = settlementText(band, band.event("pair-gig")!!, SettlementCalculator.settle(band, band.event("pair-gig")!!), "pl", words("values-pl"))
            .replace('\u00A0', ' ')
        assertTrue("Bob i Carol → Alice: 100,00 zł" in text, text)
        assertTrue("Udziały:\nAlice 100,00 zł,\nBob 100,00 zł,\nCarol 100,00 zł\n" in text, text)
    }

    @Test
    fun anOpenEventWithTheBandFundInPolish() {
        assertEquals(
            """
            Club gig · sob. 12.09

            Przychód 1 200,00 zł
            zwroty kosztów 150,00 zł
            do kasy zespołowej 100,00 zł

            Udziały:
            Alice 475,00 zł (+ zwroty 150,00 zł),
            Bob 475,00 zł

            Przekazane:
            Bob → Kasa zespołowa (Alice): 50,00 zł

            Do przekazania:
            Bob → Alice: 625,00 zł
            Bob → Kasa zespołowa (Alice): 50,00 zł

            kapelusz.jacekgajek.com
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

            kapelusz.jacekgajek.com
            """.trimIndent(),
            text(settled, "en").substringAfter("refunds),\nBob 475.00 PLN\n\n"),
        )
        assertEquals(
            """
            Przekazane:
            Bob → Kasa zespołowa (Alice): 50,00 zł
            Bob → Alice: 625,00 zł
            Bob → Kasa zespołowa (Alice): 50,00 zł

            Wszystko rozliczone

            kapelusz.jacekgajek.com
            """.trimIndent(),
            text(settled, "pl").substringAfter("150,00 zł),\nBob 475,00 zł\n\n"),
        )
    }

    @Test
    fun moneyFromTheBandFundAndNoRefunds() {
        val band = Band.create("b", "Band", "PLN", "alice", "Alice", "u-alice")
            .let { (Engine.apply(it, Actor.System, AddMember("bob", "Bob"), "m", 1) as ApplyResult.Applied).band }
            .let { (Engine.apply(it, Actor.System, LinkUser("bob", "u-bob", Role.LEAD), "l", 1) as ApplyResult.Applied).band }
            .apply(
                SetTreasurer("alice"),
                CreateEvent("gig", "Wedding", LocalDate(2026, 9, 12), participants = listOf(Participant("alice"), Participant("bob"))),
                AddHolding("gig", "h", "bob", Money(10000), IncomeSource.DOOR),
                SetFundAmount("gig", Money(-2000)),
            )
        assertEquals(
            listOf("", "Income 100.00 PLN", "from the band fund 20.00 PLN", "", "Shares:", "Alice 60.00 PLN,", "Bob 60.00 PLN", ""),
            text(band, "en").lines().subList(1, 9),
        )
    }

    /** ITERATION_3 §2.4: a fixed share says so, in both languages. */
    @Test
    fun aFixedShareSaysSo() {
        val band = gig.apply(com.jacekgajek.kapelusz.ops.SetFixedShare("gig", "bob", Money(20000)))
        assertTrue("Bob 200.00 PLN (fixed)" in text(band, "en"), text(band, "en"))
        assertTrue("Alice 750,00 zł (stała kwota)" !in text(band, "pl") && "Bob 200,00 zł (stała kwota)" in text(band, "pl"), text(band, "pl"))
    }

    /** With a problem to sort out there is nothing to pay yet: the text says the settlement is not complete. */
    @Test
    fun anEventWithAProblemIsNotSettledYet() {
        // A loss: more for the band fund than the income.
        val loss = gig.apply(SetFundAmount("gig", Money(200000)))
        assertEquals(
            listOf("Bob → Band fund (Alice): 50.00 PLN", "", "Not settled yet: there is something to sort out in the app first", "", "kapelusz.jacekgajek.com"),
            text(loss, "en").lines().takeLast(5),
        )
        assertEquals("Jeszcze nierozliczone: najpierw trzeba coś poprawić w aplikacji", text(loss, "pl").lines().dropLast(2).last())
        // Nobody has a share.
        val noShares = gig.apply(SetWeight("gig", "alice", 0), SetWeight("gig", "bob", 0))
        assertTrue("Not settled yet: there is something to sort out in the app first" in text(noShares, "en"), text(noShares, "en"))
        assertTrue("Still to pay:" !in text(noShares, "en"))
        assertTrue("Not settled yet" !in text(gig, "en"), "an open event without problems lists what is still to pay")
    }

    /** A fee still to come from the organiser: said with the totals, and in a closing sentence of its own. */
    @Test
    fun aFeeStillToComeClosesTheText() {
        val band = gig.apply(AddHolding("gig", "fee", ORGANISER, Money(30000), IncomeSource.CONTRACT))
        assertEquals(
            """
            Przychód 1 500,00 zł
            zwroty kosztów 150,00 zł
            do kasy zespołowej 100,00 zł
            Jeszcze niezapłacone: 300,00 zł
            """.trimIndent(),
            text(band, "pl").substringAfter("\n\n").substringBefore("\n\n"),
        )
        assertEquals(
            listOf("", "Reszta zostanie rozliczona po otrzymaniu zaplanowanej zapłaty", "", "kapelusz.jacekgajek.com"),
            text(band, "pl").lines().takeLast(4),
        )
    }
}
