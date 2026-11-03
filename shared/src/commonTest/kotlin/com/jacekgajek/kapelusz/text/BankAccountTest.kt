package com.jacekgajek.kapelusz.text

import com.jacekgajek.kapelusz.Scenario
import com.jacekgajek.kapelusz.engine.ApplyResult
import com.jacekgajek.kapelusz.engine.Engine
import com.jacekgajek.kapelusz.engine.isUndoable
import com.jacekgajek.kapelusz.history.BandChange
import com.jacekgajek.kapelusz.model.BAND_FUND
import com.jacekgajek.kapelusz.model.IncomeSource.DOOR
import com.jacekgajek.kapelusz.model.RejectionReason
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AddHolding
import com.jacekgajek.kapelusz.ops.DisableBandFund
import com.jacekgajek.kapelusz.ops.RenameMember
import com.jacekgajek.kapelusz.ops.SetBankAccount
import com.jacekgajek.kapelusz.ops.SetBlikPhone
import com.jacekgajek.kapelusz.ops.SetFundAmount
import com.jacekgajek.kapelusz.ops.SetFundBankAccount
import com.jacekgajek.kapelusz.ops.SetFundBlikPhone
import com.jacekgajek.kapelusz.ops.SetTreasurer
import com.jacekgajek.kapelusz.ops.Undo
import com.jacekgajek.kapelusz.ops.UnlinkUser
import com.jacekgajek.kapelusz.pln
import com.jacekgajek.kapelusz.rules.Actor
import com.jacekgajek.kapelusz.settlement.Payees
import com.jacekgajek.kapelusz.text.BankAccount
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

private const val ACCOUNT = "61109010140000071219812874"

/** Members' bank accounts, and where to pay into the band fund. */
class BankAccountTest :
    ExpectSpec({

        expect("a Polish number or any IBAN is taken when its checksum is right") {
            BankAccount.normalize("61 1090 1014 0000 0712 1981 2874") shouldBe "61109010140000071219812874"
            withClue("PL is dropped") { BankAccount.normalize("pl61-1090-1014-0000-0712-1981-2874") shouldBe "61109010140000071219812874" }
            BankAccount.normalize("de89 3704 0044 0532 0130 00") shouldBe "DE89370400440532013000"
            BankAccount.normalize("GB82 WEST 1234 5698 7654 32") shouldBe "GB82WEST12345698765432"
            // Pasted from a bank app: non-breaking and narrow spaces, invisible marks.
            BankAccount.normalize("‪61 1090 1014 0000 0712 1981 2874‬") shouldBe "61109010140000071219812874"
            withClue("a mistyped digit") { BankAccount.normalize("61 1090 1014 0000 0712 1981 2875").shouldBeNull() }
            withClue("25 digits") { BankAccount.normalize("6110901014000007121981287").shouldBeNull() }
            BankAccount.normalize("DE89 3704 0044 0532 0130 01").shouldBeNull()
            BankAccount.normalize("DE89").shouldBeNull()
            BankAccount.normalize("600 123 456").shouldBeNull()

            BankAccount.display("61109010140000071219812874") shouldBe "61 1090 1014 0000 0712 1981 2874"
            BankAccount.copyText("61109010140000071219812874") shouldBe "61109010140000071219812874"
            BankAccount.display("GB82WEST12345698765432") shouldBe "GB82 WEST 1234 5698 7654 32"
            BankAccount.copyText("GB82WEST12345698765432") shouldBe "GB82WEST12345698765432"
        }

        expect("only the member sets their own account as with BLIK") {
            val scenario = Scenario("Alice", "Bob", "Carol", "Vera")
            val bob = scenario.userFor("bob", Role.MEMBER)
            scenario.apply(SetBankAccount("bob", "PL61 1090 1014 0000 0712 1981 2874"), bob)
            scenario.band.member("bob")!!.bankAccount shouldBe ACCOUNT
            scenario.apply(SetBankAccount("carol", "DE89370400440532013000"), scenario.userFor("carol", Role.ARTIST))
            withClue("artists are paid too") { scenario.band.member("carol")!!.bankAccount shouldBe "DE89370400440532013000" }

            scenario.rejected(SetBankAccount("bob", ACCOUNT), scenario.userFor("alice", Role.OWNER)).reason shouldBe
                RejectionReason.ROLE_REQUIRED
            scenario.rejected(SetBankAccount("vera", ACCOUNT), scenario.userFor("vera", Role.VIEWER)).reason shouldBe
                RejectionReason.ROLE_REQUIRED
            scenario.rejected(SetBankAccount("bob", "123"), bob).reason shouldBe RejectionReason.INVALID_ACCOUNT
            isUndoable(SetBankAccount("bob", null)) shouldBe false

            // Undoing other changes leaves it; unlinking the account removes it.
            val before = scenario.band
            scenario.apply(RenameMember("bob", "Robert"))
            val undoRename = Undo("rename", RenameMember("bob", "Robert"), BandChange.between(before, scenario.band))
            scenario.apply(SetBankAccount("bob", "PL27114020040000300201355387"), bob)
            val undone = (Engine.apply(scenario.band, Actor.System, undoRename, "u", 99) as ApplyResult.Applied).band
            undone.member("bob")!!.name shouldBe "Bob"
            undone.member("bob")!!.bankAccount shouldBe "27114020040000300201355387"
            scenario.apply(UnlinkUser("bob"))
            scenario.band.member("bob")!!.bankAccount.shouldBeNull()
        }

        expect("the fund's holder and leads set the fund's details and they are history") {
            val scenario = Scenario("Alice", "Bob", "Carol")
            withClue("the fund is off") {
                scenario.rejected(SetFundBlikPhone("600123456")).reason shouldBe RejectionReason.NO_TREASURER
            }
            scenario.apply(SetTreasurer("bob"))
            val bob = scenario.userFor("bob", Role.MEMBER)
            scenario.apply(SetFundBlikPhone("600 123 456"), bob)
            scenario.apply(SetFundBankAccount(ACCOUNT), scenario.userFor("alice", Role.LEAD))
            scenario.band.fundBlikPhone shouldBe "600123456"
            scenario.band.fundBankAccount shouldBe ACCOUNT
            val carol = scenario.userFor("carol", Role.MEMBER)
            withClue("other members only see them") {
                scenario.rejected(SetFundBankAccount(null), carol).reason shouldBe RejectionReason.ROLE_REQUIRED
            }
            scenario.rejected(SetFundBankAccount("1234"), bob).reason shouldBe RejectionReason.INVALID_ACCOUNT
            scenario.rejected(SetFundBlikPhone("12"), bob).reason shouldBe RejectionReason.INVALID_PHONE

            // Undoable, as any change of the band's.
            val before = scenario.band
            val op = SetFundBankAccount(null)
            scenario.apply(op, bob)
            isUndoable(op) shouldBe true
            scenario.apply(Undo("u", op, BandChange.between(before, scenario.band)), bob)
            scenario.band.fundBankAccount shouldBe ACCOUNT

            // Undo looks only at the settings a change made: a rename undoes with the fund's account set since, and keeps it.
            val beforeRename = scenario.band
            val rename = com.jacekgajek.kapelusz.ops.RenameBand("New name")
            scenario.apply(rename)
            val renamed = scenario.band
            scenario.apply(SetFundBlikPhone("700 800 900"), bob)
            scenario.apply(Undo("u2", rename, BandChange.between(beforeRename, renamed)))
            scenario.band.name shouldBe beforeRename.name
            scenario.band.fundBlikPhone shouldBe "700800900"
            // But not a change whose own setting changed since.
            val beforeBlik = scenario.band
            val blik = SetFundBlikPhone("500 100 200")
            scenario.apply(blik, bob)
            val withBlik = scenario.band
            scenario.apply(SetFundBlikPhone(null), bob)
            scenario.rejected(Undo("u3", blik, BandChange.between(beforeBlik, withBlik))).reason shouldBe RejectionReason.CHANGED_SINCE

            // Turning the fund off forgets where its money was.
            scenario.apply(DisableBandFund)
            scenario.band.fundBlikPhone.shouldBeNull()
            scenario.band.fundBankAccount.shouldBeNull()
        }

        expect("a transfer to the fund shows only the fund's own details") {
            val scenario = Scenario("Alice", "Bob", "Carol").apply(SetTreasurer("alice"))
                .event("gig", "Alice", "Bob", "Carol")
                .apply(AddHolding("gig", "h", "bob", pln("300"), DOOR))
                .apply(SetFundAmount("gig", pln("90")))
            scenario.apply(SetBlikPhone("alice", "600123456"), scenario.userFor("alice", Role.OWNER))
            scenario.apply(SetBankAccount("carol", ACCOUNT), scenario.userFor("carol", Role.MEMBER))
            val settlement = scenario.settle("gig")
            withClue("never the holder's own") {
                Payees.forTransfer(scenario.band, settlement, "bob", BAND_FUND, pln("90")) shouldBe emptyList()
            }
            Payees.forHandOver(scenario.band, BAND_FUND) shouldBe emptyList()

            scenario.apply(SetFundBankAccount("DE89370400440532013000"))
            val fund = Payees.forTransfer(scenario.band, settlement, "bob", BAND_FUND, pln("90")).single()
            fund.member.shouldBeNull()
            fund.blikPhone.shouldBeNull()
            fund.bankAccount shouldBe "DE89370400440532013000"
            Payees.forHandOver(scenario.band, BAND_FUND).single() shouldBe fund

            val carol = Payees.forTransfer(scenario.band, settlement, "bob", "carol", pln("70")).single()
            carol.member!!.id shouldBe "carol"
            carol.bankAccount shouldBe ACCOUNT
            carol.blikPhone.shouldBeNull()
            // Someone with nothing given is still listed: the sheets say whom it is recorded to and who is their partner.
            Payees.forTransfer(scenario.band, settlement, "carol", "bob", pln("70")).single().bankAccount.shouldBeNull()
        }
    })
