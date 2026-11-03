package com.jacekgajek.kapelusz.app.ui.screens

import com.jacekgajek.kapelusz.api.InviteInfo
import com.jacekgajek.kapelusz.app.bands.BandRepository
import com.jacekgajek.kapelusz.app.bands.BandState
import com.jacekgajek.kapelusz.app.storage.InMemoryStore
import com.jacekgajek.kapelusz.app.ui.components.Crowd
import com.jacekgajek.kapelusz.model.Band
import com.jacekgajek.kapelusz.model.IncomeSource
import com.jacekgajek.kapelusz.model.Member
import com.jacekgajek.kapelusz.model.Money
import com.jacekgajek.kapelusz.model.RejectionReason
import com.jacekgajek.kapelusz.model.Role
import com.jacekgajek.kapelusz.ops.AddHolding
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.AddParticipant
import com.jacekgajek.kapelusz.ops.CreateEvent
import com.jacekgajek.kapelusz.ops.DeleteMember
import com.jacekgajek.kapelusz.ops.SetMemberArchived
import com.jacekgajek.kapelusz.ops.SetMemberRole
import com.jacekgajek.kapelusz.ops.UnlinkUser
import io.kotest.assertions.withClue
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.engine.coroutines.coroutineTestScope
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate

/** The band members screen's state ([RosterViewModel]) and a member's dialog ([MemberForm]), without a screen. */
@OptIn(ExperimentalCoroutinesApi::class)
class RosterViewModelTest :
    ExpectSpec({
        coroutineTestScope = true
        isolationMode = IsolationMode.InstancePerRoot
        var nextId = 0
        val invites = mutableListOf<InviteInfo>()
        var invitesFail = false
        var created = 0

        beforeTest { Dispatchers.setMain(UnconfinedTestDispatcher()) }
        afterTest { Dispatchers.resetMain() }

        /** Alice (owner, the user), Bob (no account yet), Carol. */
        suspend fun repository(scope: CoroutineScope): BandRepository {
            val repository = BandRepository(InMemoryStore(), scope, { "id${nextId++}" })
            repository.load("user-alice")
            repository.createBand("Band", "PLN", "Alice")
            repository.dispatch(AddMember("bob", "Bob")).shouldBeNull()
            repository.dispatch(AddMember("carol", "Carol")).shouldBeNull()
            return repository
        }

        fun band(repository: BandRepository): Band = repository.state.value.shouldBeInstanceOf<BandState.Ready>().band

        fun roster() = RosterViewModel(
            invitesOf = { if (invitesFail) error("offline") else invites.toList() },
            createInvite = { memberId, role ->
                InviteInfo("CODE${created++}", "band", memberId, role, expiresAt = 0).also { invites += it }
            },
            revokeInvite = { code -> invites.removeAll { it.code == code } },
        )

        fun member(repository: BandRepository, id: String): Member = band(repository).member(id)!!

        expect("lists are alphabetical with former members apart") {
            val repository = repository(coroutineTestScope)
            repository.dispatch(SetMemberArchived("carol", true)).shouldBeNull()
            val roster = roster()
            val lists = roster.lists(band(repository))
            lists.active.map { it.name } shouldBe listOf("Alice", "Bob")
            lists.archived.map { it.name } shouldBe listOf("Carol")
            withClue("three people need no search") { lists.searchable shouldBe false }
        }

        expect("a crowd gets a search that narrows former members too") {
            val repository = repository(coroutineTestScope)
            repeat(Crowd.THRESHOLD) { repository.dispatch(AddMember("m$it", "Player $it")).shouldBeNull() }
            repository.dispatch(SetMemberArchived("carol", true)).shouldBeNull()
            val roster = roster()
            roster.lists(band(repository)).searchable shouldBe true
            roster.query = "car"
            val lists = roster.lists(band(repository))
            lists.active.shouldBeEmpty()
            lists.archived.map { it.name } shouldBe listOf("Carol")
        }

        expect("the route member waits until they are in the band") {
            val repository = repository(coroutineTestScope)
            val roster = roster()
            roster.openFromRoute(null, "user-alice")
            roster.dialog.shouldBeNull()
            roster.openFromRoute(member(repository, "bob"), "user-alice")
            withClue("the band caught up: opened") { roster.dialog.shouldNotBeNull() }
        }

        expect("archiving a partner asks first and deleting unlinks first") {
            val repository = repository(coroutineTestScope)
            val roster = roster()
            roster.open(member(repository, "bob"), "user-alice", inviteKnown = true)
            val form = roster.dialog.shouldBeInstanceOf<RosterDialog.OfMember>().form
            withClue("no partner: at once") { form.toggleArchived(member(repository, "bob")) shouldBe SetMemberArchived("bob", true) }
            val paired = member(repository, "bob").copy(settlesWith = "carol")
            form.toggleArchived(paired).shouldBeNull()
            form.dialog shouldBe MemberQuestion.ConfirmArchive
            form.confirmArchive(paired) shouldBe SetMemberArchived("bob", true)
            form.deletion(member(repository, "bob")) shouldBe (null to DeleteMember("bob"))
            val linked = member(repository, "bob").copy(userId = "user-bob")
            form.deletion(linked) shouldBe (UnlinkUser("bob") to DeleteMember("bob"))
        }

        expect("invites load for leaders and the open dialog learns its members") {
            val repository = repository(coroutineTestScope)
            invites += InviteInfo("ABC123", "band", "bob", Role.MEMBER, expiresAt = 0)
            val roster = roster()
            roster.open(member(repository, "bob"), "user-alice", inviteKnown = false)
            val form = roster.dialog.shouldBeInstanceOf<RosterDialog.OfMember>().form
            form.inviteKnown shouldBe false
            roster.reloadInvites()
            roster.invitesLoaded()
            roster.inviteOf("bob")?.code shouldBe "ABC123"
            form.inviteKnown shouldBe true
            form.shownInvite?.code shouldBe "ABC123"
            invitesFail = true
            roster.reloadInvites()
            roster.invitesFailed shouldBe true
        }

        expect("the member from the route opens once only") {
            val repository = repository(coroutineTestScope)
            val roster = roster()
            roster.openFromRoute(member(repository, "bob"), "user-alice")
            roster.dialog.shouldNotBeNull()
            roster.dismiss()
            roster.openFromRoute(member(repository, "bob"), "user-alice")
            withClue("not again after a rotation") { roster.dialog.shouldBeNull() }
        }

        expect("creating an invite is refused for who holds money before asking the server") {
            val repository = repository(coroutineTestScope)
            repository.dispatch(CreateEvent("gig", "Gig", LocalDate(2026, 9, 13))).shouldBeNull()
            repository.dispatch(AddParticipant("gig", "bob")).shouldBeNull()
            repository.dispatch(AddParticipant("gig", band(repository).members.first { it.userId == "user-alice" }.id)).shouldBeNull()
            // Bob collected the hat and still owes Alice her half: he holds money until the gig is settled.
            repository.dispatch(AddHolding("gig", "h1", "bob", Money.parse("100"), IncomeSource.HAT)).shouldBeNull()
            val roster = roster()
            roster.open(member(repository, "bob"), "user-alice", inviteKnown = true)
            val form = roster.dialog.shouldBeInstanceOf<RosterDialog.OfMember>().form
            form.inviteRole = Role.ARTIST
            val view = repository.state.value.shouldBeInstanceOf<BandState.Ready>().view
            form.create(view) shouldBe InviteOutcome.Refused(RejectionReason.HOLDS_MONEY)
            created shouldBe 0
            form.inviteRole = Role.MEMBER
            form.create(view).shouldBeNull()
            form.shownInvite?.code shouldBe "CODE0"
            form.revoke().shouldBeNull()
            form.shownInvite.shouldBeNull()
            invites.shouldBeEmpty()
        }

        expect("a role change asks first when it is your own") {
            val repository = repository(coroutineTestScope)
            repository.dispatch(SetMemberRole("carol", Role.LEAD)).shouldBeNull()
            val roster = roster()
            roster.open(member(repository, "carol"), "user-alice", inviteKnown = true)
            val form = roster.dialog.shouldBeInstanceOf<RosterDialog.OfMember>().form
            withClue("the same role: nothing") { form.pickRole(member(repository, "carol"), Role.LEAD).shouldBeNull() }
            form.pickRole(member(repository, "carol"), Role.MEMBER) shouldBe SetMemberRole("carol", Role.MEMBER)
            val alice = band(repository).members.first { it.userId == "user-alice" }
            roster.open(alice, "user-alice", inviteKnown = true)
            val own = roster.dialog.shouldBeInstanceOf<RosterDialog.OfMember>().form
            own.pickRole(alice, Role.LEAD).shouldBeNull()
            own.dialog shouldBe MemberQuestion.ConfirmRole(Role.LEAD, own = true)
            own.confirmRole(alice) shouldBe SetMemberRole(alice.id, Role.LEAD)
            own.dialog.shouldBeNull()
        }

        expect("renaming needs a new free name") {
            val repository = repository(coroutineTestScope)
            val roster = roster()
            roster.open(member(repository, "bob"), "user-alice", inviteKnown = true)
            val form = roster.dialog.shouldBeInstanceOf<RosterDialog.OfMember>().form
            withClue("unchanged") { form.rename(member(repository, "bob"), taken = false).shouldBeNull() }
            form.name = "Carol"
            form.rename(member(repository, "bob"), taken = true).shouldBeNull()
            form.name = "Robert"
            form.rename(member(repository, "bob"), taken = false).shouldNotBeNull()
        }
    })
