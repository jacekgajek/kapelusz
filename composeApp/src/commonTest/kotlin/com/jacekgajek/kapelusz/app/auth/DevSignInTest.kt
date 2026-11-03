package com.jacekgajek.kapelusz.app.auth

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith

class DevSignInTest :
    ExpectSpec({
        expect("the token reads as a Google one for the same name every time") {
            val now = 1_800_000_000_000
            val session = Session.fromIdToken(devIdToken("Zośka \"Z\" Nowak", now))
            session.userId shouldBe "dev-zo-ka-z-nowak"
            session.name shouldBe "Zośka \"Z\" Nowak"
            session.email shouldBe "zo-ka-z-nowak@dev.local"
            withClue("never renewed through Google") { session.expiresAt shouldBeGreaterThan now + 300L * 24 * 3600 * 1000 }
            Session.fromIdToken(devIdToken("Zośka \"Z\" Nowak", now + 1)).userId shouldBe session.userId
            withClue("unsigned") { devIdToken("Alice", now) shouldEndWith "." }
        }

        expect("a name without letters or digits signs in as nobody") {
            devUserId(" !! ").shouldBeNull()
            devUserId("  Bob ") shouldBe "dev-bob"
        }
    })
