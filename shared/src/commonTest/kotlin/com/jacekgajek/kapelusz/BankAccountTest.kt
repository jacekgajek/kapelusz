package com.jacekgajek.kapelusz

import com.jacekgajek.kapelusz.auth.Actor
import com.jacekgajek.kapelusz.domain.BankAccount
import com.jacekgajek.kapelusz.domain.Payees
import com.jacekgajek.kapelusz.model.BAND_FUND
import com.jacekgajek.kapelusz.model.IncomeSource.DOOR
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AddHolding
import com.jacekgajek.kapelusz.ops.ApplyResult
import com.jacekgajek.kapelusz.ops.BandChange
import com.jacekgajek.kapelusz.ops.DisableBandFund
import com.jacekgajek.kapelusz.ops.Engine
import com.jacekgajek.kapelusz.ops.RejectionReason
import com.jacekgajek.kapelusz.ops.RenameMember
import com.jacekgajek.kapelusz.ops.SetBankAccount
import com.jacekgajek.kapelusz.ops.SetBlikPhone
import com.jacekgajek.kapelusz.ops.SetFundAmount
import com.jacekgajek.kapelusz.ops.SetFundBankAccount
import com.jacekgajek.kapelusz.ops.SetFundBlikPhone
import com.jacekgajek.kapelusz.ops.SetTreasurer
import com.jacekgajek.kapelusz.ops.Undo
import com.jacekgajek.kapelusz.ops.UnlinkUser
import com.jacekgajek.kapelusz.ops.isUndoable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ITERATION_7 §2i: members' bank accounts, and where to pay into the band fund. */
class BankAccountTest {

    @Test
    fun aPolishNumberOrAnyIbanIsTakenWhenItsChecksumIsRight() {
        assertEquals("61109010140000071219812874", BankAccount.normalize("61 1090 1014 0000 0712 1981 2874"))
        assertEquals("61109010140000071219812874", BankAccount.normalize("pl61-1090-1014-0000-0712-1981-2874"), "PL is dropped")
        assertEquals("DE89370400440532013000", BankAccount.normalize("de89 3704 0044 0532 0130 00"))
        assertEquals("GB82WEST12345698765432", BankAccount.normalize("GB82 WEST 1234 5698 7654 32"))
        // Pasted from a bank app: non-breaking and narrow spaces, invisible marks.
        assertEquals("61109010140000071219812874", BankAccount.normalize("‪61 1090 1014 0000 0712 1981 2874‬"))
        assertNull(BankAccount.normalize("61 1090 1014 0000 0712 1981 2875"), "a mistyped digit")
        assertNull(BankAccount.normalize("6110901014000007121981287"), "25 digits")
        assertNull(BankAccount.normalize("DE89 3704 0044 0532 0130 01"))
        assertNull(BankAccount.normalize("DE89"))
        assertNull(BankAccount.normalize("600 123 456"))

        assertEquals("61 1090 1014 0000 0712 1981 2874", BankAccount.display("61109010140000071219812874"))
        assertEquals("61109010140000071219812874", BankAccount.copyText("61109010140000071219812874"))
        assertEquals("GB82 WEST 1234 5698 7654 32", BankAccount.display("GB82WEST12345698765432"))
        assertEquals("GB82WEST12345698765432", BankAccount.copyText("GB82WEST12345698765432"))
    }

    @Test
    fun onlyTheMemberSetsTheirOwnAccountAsWithBlik() {
        val scenario = Scenario("Alice", "Bob", "Carol", "Vera")
        val bob = scenario.userFor("bob", Role.MEMBER)
        scenario.apply(SetBankAccount("bob", "PL61 1090 1014 0000 0712 1981 2874"), bob)
        assertEquals(ACCOUNT, scenario.band.member("bob")!!.bankAccount)
        scenario.apply(SetBankAccount("carol", "DE89370400440532013000"), scenario.userFor("carol", Role.ARTIST))
        assertEquals("DE89370400440532013000", scenario.band.member("carol")!!.bankAccount, "artists are paid too")

        assertEquals(RejectionReason.ROLE_REQUIRED, scenario.rejected(SetBankAccount("bob", ACCOUNT), scenario.userFor("alice", Role.OWNER)).reason)
        assertEquals(RejectionReason.ROLE_REQUIRED, scenario.rejected(SetBankAccount("vera", ACCOUNT), scenario.userFor("vera", Role.VIEWER)).reason)
        assertEquals(RejectionReason.INVALID_ACCOUNT, scenario.rejected(SetBankAccount("bob", "123"), bob).reason)
        assertFalse(isUndoable(SetBankAccount("bob", null)))

        // Undoing other changes leaves it; unlinking the account removes it.
        val before = scenario.band
        scenario.apply(RenameMember("bob", "Robert"))
        val undoRename = Undo("rename", RenameMember("bob", "Robert"), BandChange.between(before, scenario.band))
        scenario.apply(SetBankAccount("bob", "PL27114020040000300201355387"), bob)
        val undone = (Engine.apply(scenario.band, Actor.System, undoRename, "u", 99) as ApplyResult.Applied).band
        assertEquals("Bob", undone.member("bob")!!.name)
        assertEquals("27114020040000300201355387", undone.member("bob")!!.bankAccount)
        scenario.apply(UnlinkUser("bob"))
        assertNull(scenario.band.member("bob")!!.bankAccount)
    }

    @Test
    fun theFundsHolderAndLeadsSetTheFundsDetailsAndTheyAreHistory() {
        val scenario = Scenario("Alice", "Bob", "Carol")
        assertEquals(RejectionReason.NO_TREASURER, scenario.rejected(SetFundBlikPhone("600123456")).reason, "the fund is off")
        scenario.apply(SetTreasurer("bob"))
        val bob = scenario.userFor("bob", Role.MEMBER)
        scenario.apply(SetFundBlikPhone("600 123 456"), bob)
        scenario.apply(SetFundBankAccount(ACCOUNT), scenario.userFor("alice", Role.LEAD))
        assertEquals("600123456", scenario.band.fundBlikPhone)
        assertEquals(ACCOUNT, scenario.band.fundBankAccount)
        val carol = scenario.userFor("carol", Role.MEMBER)
        assertEquals(RejectionReason.ROLE_REQUIRED, scenario.rejected(SetFundBankAccount(null), carol).reason, "other members only see them")
        assertEquals(RejectionReason.INVALID_ACCOUNT, scenario.rejected(SetFundBankAccount("1234"), bob).reason)
        assertEquals(RejectionReason.INVALID_PHONE, scenario.rejected(SetFundBlikPhone("12"), bob).reason)

        // Undoable, as any change of the band's.
        val before = scenario.band
        val op = SetFundBankAccount(null)
        scenario.apply(op, bob)
        assertTrue(isUndoable(op))
        scenario.apply(Undo("u", op, BandChange.between(before, scenario.band)), bob)
        assertEquals(ACCOUNT, scenario.band.fundBankAccount)

        // Undo looks only at the settings a change made: a rename undoes with the fund's account set since, and keeps it.
        val beforeRename = scenario.band
        val rename = com.jacekgajek.kapelusz.ops.RenameBand("New name")
        scenario.apply(rename)
        val renamed = scenario.band
        scenario.apply(SetFundBlikPhone("700 800 900"), bob)
        scenario.apply(Undo("u2", rename, BandChange.between(beforeRename, renamed)))
        assertEquals(beforeRename.name, scenario.band.name)
        assertEquals("700800900", scenario.band.fundBlikPhone)
        // But not a change whose own setting changed since.
        val beforeBlik = scenario.band
        val blik = SetFundBlikPhone("500 100 200")
        scenario.apply(blik, bob)
        val withBlik = scenario.band
        scenario.apply(SetFundBlikPhone(null), bob)
        assertEquals(RejectionReason.CHANGED_SINCE, scenario.rejected(Undo("u3", blik, BandChange.between(beforeBlik, withBlik))).reason)

        // Turning the fund off forgets where its money was.
        scenario.apply(DisableBandFund)
        assertNull(scenario.band.fundBlikPhone)
        assertNull(scenario.band.fundBankAccount)
    }

    @Test
    fun aTransferToTheFundShowsOnlyTheFundsOwnDetails() {
        val scenario = Scenario("Alice", "Bob", "Carol").apply(SetTreasurer("alice"))
            .event("gig", "Alice", "Bob", "Carol")
            .apply(AddHolding("gig", "h", "bob", pln("300"), DOOR))
            .apply(SetFundAmount("gig", pln("90")))
        scenario.apply(SetBlikPhone("alice", "600123456"), scenario.userFor("alice", Role.OWNER))
        scenario.apply(SetBankAccount("carol", ACCOUNT), scenario.userFor("carol", Role.MEMBER))
        val settlement = scenario.settle("gig")
        assertEquals(emptyList(), Payees.forTransfer(scenario.band, settlement, "bob", BAND_FUND, pln("90")), "never the holder's own")
        assertEquals(emptyList(), Payees.forHandOver(scenario.band, BAND_FUND))

        scenario.apply(SetFundBankAccount("DE89370400440532013000"))
        val fund = Payees.forTransfer(scenario.band, settlement, "bob", BAND_FUND, pln("90")).single()
        assertNull(fund.member)
        assertNull(fund.blikPhone)
        assertEquals("DE89370400440532013000", fund.bankAccount)
        assertEquals(fund, Payees.forHandOver(scenario.band, BAND_FUND).single())

        val carol = Payees.forTransfer(scenario.band, settlement, "bob", "carol", pln("70")).single()
        assertEquals("carol", carol.member!!.id)
        assertEquals(ACCOUNT, carol.bankAccount)
        assertNull(carol.blikPhone)
        // Someone with nothing given is still listed: the sheets say whom it is recorded to and who is their partner.
        assertNull(Payees.forTransfer(scenario.band, settlement, "carol", "bob", pln("70")).single().bankAccount)
    }

    private companion object {
        const val ACCOUNT = "61109010140000071219812874"
    }
}
