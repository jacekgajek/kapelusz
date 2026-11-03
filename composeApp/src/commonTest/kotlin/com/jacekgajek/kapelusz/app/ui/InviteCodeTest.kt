package com.jacekgajek.kapelusz.app.ui

import com.jacekgajek.kapelusz.app.ui.screens.inviteCode
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/** What a scanned invite QR code gives the Join dialog. */
class InviteCodeTest :
    ExpectSpec({
        expect("a join link's code") {
            inviteCode("https://kapelusz.jacekgajek.com/join/T2KTHM") shouldBe "T2KTHM"
            inviteCode(" https://kapelusz.jacekgajek.com/join/t2kthm/ ") shouldBe "T2KTHM"
            inviteCode("https://kapelusz.jacekgajek.com/join/T2KTHM?utm=qr") shouldBe "T2KTHM"
        }

        expect("a bare code") {
            inviteCode("abc123") shouldBe "ABC123"
        }

        expect("anything else is no invite") {
            inviteCode("https://example.com/menu").shouldBeNull()
            inviteCode("https://kapelusz.jacekgajek.com/join/TOOLONG1").shouldBeNull()
            inviteCode("hello!").shouldBeNull()
            withClue("only A–Z and 0–9") { inviteCode("ĄBC123").shouldBeNull() }
        }
    })
