package com.jacekgajek.kapelusz.text

import com.jacekgajek.kapelusz.Scenario
import com.jacekgajek.kapelusz.model.RejectionReason
import com.jacekgajek.kapelusz.ops.AddMember
import com.jacekgajek.kapelusz.ops.RenameMember
import com.jacekgajek.kapelusz.ops.SetMemberArchived
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe

/** Member names are unique, ignoring case, archived members included. */
class MemberNamesTest :
    ExpectSpec({
        expect("a name in use is refused when adding or renaming") {
            val scenario = Scenario("Alice", "Ola")
            scenario.rejected(AddMember("x", " ola ")).reason shouldBe RejectionReason.NAME_TAKEN
            scenario.rejected(RenameMember("alice", "OLA")).reason shouldBe RejectionReason.NAME_TAKEN
            scenario.apply(RenameMember("ola", "Ola"))
            scenario.apply(RenameMember("ola", "Ola K."))
            scenario.apply(SetMemberArchived("ola", true))
            withClue("archived members keep their names") {
                scenario.rejected(AddMember("y", "Ola K.")).reason shouldBe RejectionReason.NAME_TAKEN
            }
        }
    })
