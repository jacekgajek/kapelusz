package com.jacekgajek.kapelusz

import com.jacekgajek.kapelusz.domain.BlikPhone
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.ApplyResult
import com.jacekgajek.kapelusz.ops.BandChange
import com.jacekgajek.kapelusz.ops.Undo
import com.jacekgajek.kapelusz.ops.Engine
import com.jacekgajek.kapelusz.ops.RejectionReason
import com.jacekgajek.kapelusz.ops.RenameMember
import com.jacekgajek.kapelusz.ops.SetBlikPhone
import com.jacekgajek.kapelusz.ops.UnlinkUser
import com.jacekgajek.kapelusz.ops.isUndoable
import com.jacekgajek.kapelusz.auth.Actor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** ITERATION_5 §8: a member's BLIK phone number. */
class BlikPhoneTest {

    @Test
    fun anyPhoneNumberIsTakenAndAPolishOneIsShownInThrees() {
        assertEquals("600123456", BlikPhone.normalize(" 600-123 456 "))
        assertEquals("+48600123456", BlikPhone.normalize("0048 600 123 456"))
        assertEquals("+4915112345678", BlikPhone.normalize("+49 (151) 1234-5678"))
        assertNull(BlikPhone.normalize("12345"), "too short")
        assertNull(BlikPhone.normalize("1234567890123456"), "too long")
        assertNull(BlikPhone.normalize("600 12a 456"))
        assertNull(BlikPhone.normalize("6+00123456"))
        assertNull(BlikPhone.normalize("+0123456789"), "no country code starts with 0")
        assertNull(BlikPhone.normalize("+00 48 600 123 456"))
        // Pasted from a contacts app: invisible direction marks, narrow and non-breaking spaces, a non-breaking hyphen.
        assertEquals("+48600123456", BlikPhone.normalize("\u202A+48\u202F600\u00A0123\u2011456\u202C\t"))

        assertEquals("600 123 456", BlikPhone.display("600123456"))
        assertEquals("600 123 456", BlikPhone.display("+48600123456"))
        assertEquals("600123456", BlikPhone.copyText("+48600123456"), "bank apps take a Polish number without +48")
        assertEquals("+4915112345678", BlikPhone.display("+4915112345678"))
        assertEquals("+4915112345678", BlikPhone.copyText("+4915112345678"))
    }

    @Test
    fun onlyTheMemberSetsTheirOwnNumberAndAViewerNone() {
        val scenario = Scenario("Alice", "Bob", "Carol", "Vera")
        val bob = scenario.userFor("bob", Role.MEMBER)
        val artist = scenario.userFor("carol", Role.ARTIST)
        scenario.apply(SetBlikPhone("bob", "+48 600 123 456"), bob)
        assertEquals("+48600123456", scenario.band.member("bob")!!.blikPhone)
        scenario.apply(SetBlikPhone("carol", "500 100 200"), artist)
        assertEquals("500100200", scenario.band.member("carol")!!.blikPhone, "artists are paid too")

        val owner = scenario.userFor("alice", Role.OWNER)
        assertEquals(RejectionReason.ROLE_REQUIRED, scenario.rejected(SetBlikPhone("bob", "700700700"), owner).reason, "not even the owner")
        val viewer = scenario.userFor("vera", Role.VIEWER)
        assertEquals(RejectionReason.ROLE_REQUIRED, scenario.rejected(SetBlikPhone("vera", "700700700"), viewer).reason)
        assertEquals(RejectionReason.INVALID_PHONE, scenario.rejected(SetBlikPhone("bob", "123"), bob).reason)

        scenario.apply(SetBlikPhone("bob", null), bob)
        assertNull(scenario.band.member("bob")!!.blikPhone)
        assertFalse(isUndoable(SetBlikPhone("bob", null)))
    }

    @Test
    fun theNumberGoesWithTheAccountAndUndoLeavesIt() {
        val scenario = Scenario("Alice", "Bob")
        val bob = scenario.userFor("bob", Role.MEMBER)
        val before = scenario.band
        scenario.apply(RenameMember("bob", "Robert"))
        val undoRename = Undo("rename", RenameMember("bob", "Robert"), BandChange.between(before, scenario.band))
        scenario.apply(SetBlikPhone("bob", "600123456"), bob)
        val undone = (Engine.apply(scenario.band, Actor.System, undoRename, "u", 99) as ApplyResult.Applied).band
        assertEquals("Bob", undone.member("bob")!!.name, "a number set since doesn't block undoing a rename")
        assertEquals("600123456", undone.member("bob")!!.blikPhone, "and stays")

        scenario.apply(UnlinkUser("bob"))
        assertNull(scenario.band.member("bob")!!.blikPhone)
    }

    @Test
    fun onlyThePayingSideSeesTheNumbers() {
        val scenario = Scenario("Alice", "Bob", "Carol")
        scenario.apply(com.jacekgajek.kapelusz.ops.SetTreasurer("alice"))
        assertEquals(true, BlikPhone.pays(scenario.band, null, "bob", "bob"))
        assertEquals(false, BlikPhone.pays(scenario.band, null, "carol", "bob"), "the recipient doesn't")
        assertEquals(true, BlikPhone.pays(scenario.band, null, "alice", com.jacekgajek.kapelusz.model.BAND_FUND), "the fund's holder pays from it")
        assertEquals(false, BlikPhone.pays(scenario.band, null, "bob", com.jacekgajek.kapelusz.model.BAND_FUND))
        assertEquals(false, BlikPhone.pays(scenario.band, null, null, "bob"))
    }
}
