package com.jacekgajek.kapelusz.app.ui

import com.jacekgajek.kapelusz.app.ui.screens.BandHint
import com.jacekgajek.kapelusz.app.ui.screens.bandHint
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.Role
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/** What a band still lacks, for its leaders. */
class BandHintTest :
    ExpectSpec({
        val solo = Band.create(id = "b", name = "Band", currency = "PLN", ownerMemberId = "o", ownerName = "Olga", ownerUserId = "u-o")
        val owner = solo.member("o")!!

        expect("a band with only its owner has no members and that can't be hidden") {
            bandHint(solo, owner, hiddenNoApp = true) shouldBe BandHint.NO_MEMBERS
        }

        expect("members without the app get the invite hint until hidden or linked") {
            val withMembers = solo.copy(members = solo.members + owner.copy(id = "b1", name = "Bob", userId = null, role = Role.MEMBER))
            bandHint(withMembers, owner, hiddenNoApp = false) shouldBe BandHint.NO_APP
            bandHint(withMembers, owner, hiddenNoApp = true).shouldBeNull()
            val linked = withMembers.copy(members = withMembers.members.map { if (it.id == "b1") it.copy(userId = "u-b") else it })
            bandHint(linked, owner, hiddenNoApp = false).shouldBeNull()
            val archivedOnly = withMembers.copy(members = withMembers.members.map { if (it.id == "b1") it.copy(archived = true) else it })
            withClue("archived members don't count") { bandHint(archivedOnly, owner, hiddenNoApp = false) shouldBe BandHint.NO_MEMBERS }
        }

        expect("only leaders see hints") {
            val withMembers = solo.copy(members = solo.members + owner.copy(id = "b1", name = "Bob", userId = "u-b", role = Role.MEMBER))
            bandHint(withMembers, withMembers.member("b1"), hiddenNoApp = false).shouldBeNull()
            bandHint(solo, null, hiddenNoApp = false).shouldBeNull()
        }
    })
