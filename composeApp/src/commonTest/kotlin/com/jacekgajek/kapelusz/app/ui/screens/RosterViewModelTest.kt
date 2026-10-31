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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate

/** The band members screen's state ([RosterViewModel]) and a member's dialog ([MemberForm]), without a screen. */
@OptIn(ExperimentalCoroutinesApi::class)
class RosterViewModelTest {
    private var nextId = 0
    private val invites = mutableListOf<InviteInfo>()
    private var invitesFail = false
    private var created = 0

    @BeforeTest
    fun useTestMain() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun reset() = Dispatchers.resetMain()

    /** Alice (owner, the user), Bob (no account yet), Carol. */
    private suspend fun TestScope.repository(): BandRepository {
        val repository = BandRepository(InMemoryStore(), this, { "id${nextId++}" })
        repository.load("user-alice")
        repository.createBand("Band", "PLN", "Alice")
        assertNull(repository.dispatch(AddMember("bob", "Bob")))
        assertNull(repository.dispatch(AddMember("carol", "Carol")))
        return repository
    }

    private fun band(repository: BandRepository): Band = assertIs<BandState.Ready>(repository.state.value).band

    private fun roster() = RosterViewModel(
        invitesOf = { if (invitesFail) error("offline") else invites.toList() },
        createInvite = { memberId, role -> InviteInfo("CODE${created++}", "band", memberId, role, expiresAt = 0).also { invites += it } },
        revokeInvite = { code -> invites.removeAll { it.code == code } },
    )

    private fun member(repository: BandRepository, id: String): Member = band(repository).member(id)!!

    @Test
    fun listsAreAlphabeticalWithFormerMembersApart() = runTest {
        val repository = repository()
        assertNull(repository.dispatch(SetMemberArchived("carol", true)))
        val roster = roster()
        val lists = roster.lists(band(repository))
        assertEquals(listOf("Alice", "Bob"), lists.active.map { it.name })
        assertEquals(listOf("Carol"), lists.archived.map { it.name })
        assertFalse(lists.searchable, "three people need no search")
    }

    @Test
    fun aCrowdGetsASearchThatNarrowsFormerMembersToo() = runTest {
        val repository = repository()
        repeat(Crowd.THRESHOLD) { assertNull(repository.dispatch(AddMember("m$it", "Player $it"))) }
        assertNull(repository.dispatch(SetMemberArchived("carol", true)))
        val roster = roster()
        assertTrue(roster.lists(band(repository)).searchable)
        roster.query = "car"
        val lists = roster.lists(band(repository))
        assertEquals(emptyList(), lists.active)
        assertEquals(listOf("Carol"), lists.archived.map { it.name })
    }

    @Test
    fun theRouteMemberWaitsUntilTheyAreInTheBand() = runTest {
        val repository = repository()
        val roster = roster()
        roster.openFromRoute(null, "user-alice")
        assertNull(roster.dialog)
        roster.openFromRoute(member(repository, "bob"), "user-alice")
        assertNotNull(roster.dialog, "the band caught up: opened")
    }

    @Test
    fun archivingAPartnerAsksFirstAndDeletingUnlinksFirst() = runTest {
        val repository = repository()
        val roster = roster()
        roster.open(member(repository, "bob"), "user-alice", inviteKnown = true)
        val form = assertIs<RosterDialog.OfMember>(roster.dialog).form
        assertEquals(SetMemberArchived("bob", true), form.toggleArchived(member(repository, "bob")), "no partner: at once")
        val paired = member(repository, "bob").copy(settlesWith = "carol")
        assertNull(form.toggleArchived(paired))
        assertEquals(MemberQuestion.ConfirmArchive, form.dialog)
        assertEquals(SetMemberArchived("bob", true), form.confirmArchive(paired))
        assertEquals(null to DeleteMember("bob"), form.deletion(member(repository, "bob")))
        val linked = member(repository, "bob").copy(userId = "user-bob")
        assertEquals(UnlinkUser("bob") to DeleteMember("bob"), form.deletion(linked))
    }

    @Test
    fun invitesLoadForLeadersAndTheOpenDialogLearnsItsMembers() = runTest {
        val repository = repository()
        invites += InviteInfo("ABC123", "band", "bob", Role.MEMBER, expiresAt = 0)
        val roster = roster()
        roster.open(member(repository, "bob"), "user-alice", inviteKnown = false)
        val form = assertIs<RosterDialog.OfMember>(roster.dialog).form
        assertFalse(form.inviteKnown)
        roster.reloadInvites()
        roster.invitesLoaded()
        assertEquals("ABC123", roster.inviteOf("bob")?.code)
        assertTrue(form.inviteKnown)
        assertEquals("ABC123", form.shownInvite?.code)
        invitesFail = true
        roster.reloadInvites()
        assertTrue(roster.invitesFailed)
    }

    @Test
    fun theMemberFromTheRouteOpensOnceOnly() = runTest {
        val repository = repository()
        val roster = roster()
        roster.openFromRoute(member(repository, "bob"), "user-alice")
        assertNotNull(roster.dialog)
        roster.dismiss()
        roster.openFromRoute(member(repository, "bob"), "user-alice")
        assertNull(roster.dialog, "not again after a rotation")
    }

    @Test
    fun creatingAnInviteIsRefusedForWhoHoldsMoneyBeforeAskingTheServer() = runTest {
        val repository = repository()
        assertNull(repository.dispatch(CreateEvent("gig", "Gig", LocalDate(2026, 9, 13))))
        assertNull(repository.dispatch(AddParticipant("gig", "bob")))
        assertNull(repository.dispatch(AddParticipant("gig", band(repository).members.first { it.userId == "user-alice" }.id)))
        // Bob collected the hat and still owes Alice her half: he holds money until the gig is settled.
        assertNull(repository.dispatch(AddHolding("gig", "h1", "bob", Money.parse("100"), IncomeSource.HAT)))
        val roster = roster()
        roster.open(member(repository, "bob"), "user-alice", inviteKnown = true)
        val form = assertIs<RosterDialog.OfMember>(roster.dialog).form
        form.inviteRole = Role.ARTIST
        val view = assertIs<BandState.Ready>(repository.state.value).view
        assertEquals(InviteOutcome.Refused(RejectionReason.HOLDS_MONEY), form.create(view))
        assertEquals(0, created)
        form.inviteRole = Role.MEMBER
        assertNull(form.create(view))
        assertEquals("CODE0", form.shownInvite?.code)
        assertNull(form.revoke())
        assertNull(form.shownInvite)
        assertTrue(invites.isEmpty())
    }

    @Test
    fun aRoleChangeAsksFirstWhenItIsYourOwn() = runTest {
        val repository = repository()
        assertNull(repository.dispatch(SetMemberRole("carol", Role.LEAD)))
        val roster = roster()
        roster.open(member(repository, "carol"), "user-alice", inviteKnown = true)
        val form = assertIs<RosterDialog.OfMember>(roster.dialog).form
        assertNull(form.pickRole(member(repository, "carol"), Role.LEAD), "the same role: nothing")
        assertEquals(SetMemberRole("carol", Role.MEMBER), form.pickRole(member(repository, "carol"), Role.MEMBER))
        val alice = band(repository).members.first { it.userId == "user-alice" }
        roster.open(alice, "user-alice", inviteKnown = true)
        val own = assertIs<RosterDialog.OfMember>(roster.dialog).form
        assertNull(own.pickRole(alice, Role.LEAD))
        assertEquals(MemberQuestion.ConfirmRole(Role.LEAD, own = true), own.dialog)
        assertEquals(SetMemberRole(alice.id, Role.LEAD), own.confirmRole(alice))
        assertNull(own.dialog)
    }

    @Test
    fun renamingNeedsANewFreeName() = runTest {
        val repository = repository()
        val roster = roster()
        roster.open(member(repository, "bob"), "user-alice", inviteKnown = true)
        val form = assertIs<RosterDialog.OfMember>(roster.dialog).form
        assertNull(form.rename(member(repository, "bob"), taken = false), "unchanged")
        form.name = "Carol"
        assertNull(form.rename(member(repository, "bob"), taken = true))
        form.name = "Robert"
        assertNotNull(form.rename(member(repository, "bob"), taken = false))
    }
}
