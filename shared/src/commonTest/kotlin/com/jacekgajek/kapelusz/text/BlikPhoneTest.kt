package com.jacekgajek.kapelusz.text

import com.jacekgajek.kapelusz.Scenario
import com.jacekgajek.kapelusz.engine.ApplyResult
import com.jacekgajek.kapelusz.engine.Engine
import com.jacekgajek.kapelusz.engine.isUndoable
import com.jacekgajek.kapelusz.history.BandChange
import com.jacekgajek.kapelusz.model.RejectionReason
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.RenameMember
import com.jacekgajek.kapelusz.ops.SetBlikPhone
import com.jacekgajek.kapelusz.ops.Undo
import com.jacekgajek.kapelusz.ops.UnlinkUser
import com.jacekgajek.kapelusz.rules.Actor
import com.jacekgajek.kapelusz.text.BlikPhone
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/** A member's BLIK phone number. */
class BlikPhoneTest :
    ExpectSpec({

        expect("any phone number is taken and a Polish one is shown in threes") {
            BlikPhone.normalize(" 600-123 456 ") shouldBe "600123456"
            BlikPhone.normalize("0048 600 123 456") shouldBe "+48600123456"
            BlikPhone.normalize("+49 (151) 1234-5678") shouldBe "+4915112345678"
            withClue("too short") { BlikPhone.normalize("12345").shouldBeNull() }
            withClue("too long") { BlikPhone.normalize("1234567890123456").shouldBeNull() }
            BlikPhone.normalize("600 12a 456").shouldBeNull()
            BlikPhone.normalize("6+00123456").shouldBeNull()
            withClue("no country code starts with 0") { BlikPhone.normalize("+0123456789").shouldBeNull() }
            BlikPhone.normalize("+00 48 600 123 456").shouldBeNull()
            // Pasted from a contacts app: invisible direction marks, narrow and non-breaking spaces, a non-breaking hyphen.
            BlikPhone.normalize("\u202A+48\u202F600\u00A0123\u2011456\u202C\t") shouldBe "+48600123456"

            BlikPhone.display("600123456") shouldBe "600 123 456"
            BlikPhone.display("+48600123456") shouldBe "600 123 456"
            withClue("bank apps take a Polish number without +48") { BlikPhone.copyText("+48600123456") shouldBe "600123456" }
            BlikPhone.display("+4915112345678") shouldBe "+4915112345678"
            BlikPhone.copyText("+4915112345678") shouldBe "+4915112345678"
        }

        expect("only the member sets their own number and a viewer none") {
            val scenario = Scenario("Alice", "Bob", "Carol", "Vera")
            val bob = scenario.userFor("bob", Role.MEMBER)
            val artist = scenario.userFor("carol", Role.ARTIST)
            scenario.apply(SetBlikPhone("bob", "+48 600 123 456"), bob)
            scenario.band.member("bob")!!.blikPhone shouldBe "+48600123456"
            scenario.apply(SetBlikPhone("carol", "500 100 200"), artist)
            withClue("artists are paid too") { scenario.band.member("carol")!!.blikPhone shouldBe "500100200" }

            val owner = scenario.userFor("alice", Role.OWNER)
            withClue("not even the owner") {
                scenario.rejected(SetBlikPhone("bob", "700700700"), owner).reason shouldBe RejectionReason.ROLE_REQUIRED
            }
            val viewer = scenario.userFor("vera", Role.VIEWER)
            scenario.rejected(SetBlikPhone("vera", "700700700"), viewer).reason shouldBe RejectionReason.ROLE_REQUIRED
            scenario.rejected(SetBlikPhone("bob", "123"), bob).reason shouldBe RejectionReason.INVALID_PHONE

            scenario.apply(SetBlikPhone("bob", null), bob)
            scenario.band.member("bob")!!.blikPhone.shouldBeNull()
            isUndoable(SetBlikPhone("bob", null)) shouldBe false
        }

        expect("the number goes with the account and undo leaves it") {
            val scenario = Scenario("Alice", "Bob")
            val bob = scenario.userFor("bob", Role.MEMBER)
            val before = scenario.band
            scenario.apply(RenameMember("bob", "Robert"))
            val undoRename = Undo("rename", RenameMember("bob", "Robert"), BandChange.between(before, scenario.band))
            scenario.apply(SetBlikPhone("bob", "600123456"), bob)
            val undone = (Engine.apply(scenario.band, Actor.System, undoRename, "u", 99) as ApplyResult.Applied).band
            withClue("a number set since doesn't block undoing a rename") { undone.member("bob")!!.name shouldBe "Bob" }
            withClue("and stays") { undone.member("bob")!!.blikPhone shouldBe "600123456" }

            scenario.apply(UnlinkUser("bob"))
            scenario.band.member("bob")!!.blikPhone.shouldBeNull()
        }

        expect("only the paying side sees the numbers") {
            val scenario = Scenario("Alice", "Bob", "Carol")
            scenario.apply(com.jacekgajek.kapelusz.ops.SetTreasurer("alice"))
            BlikPhone.pays(scenario.band, null, "bob", "bob") shouldBe true
            withClue("the recipient doesn't") { BlikPhone.pays(scenario.band, null, "carol", "bob") shouldBe false }
            withClue("the fund's holder pays from it") {
                BlikPhone.pays(scenario.band, null, "alice", com.jacekgajek.kapelusz.model.BAND_FUND) shouldBe true
            }
            BlikPhone.pays(scenario.band, null, "bob", com.jacekgajek.kapelusz.model.BAND_FUND) shouldBe false
            BlikPhone.pays(scenario.band, null, null, "bob") shouldBe false
        }
    })
