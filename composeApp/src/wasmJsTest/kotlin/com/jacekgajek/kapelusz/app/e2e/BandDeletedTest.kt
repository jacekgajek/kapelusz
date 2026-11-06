package com.jacekgajek.kapelusz.app.e2e

import com.jacekgajek.kapelusz.app.generated.Res
import com.jacekgajek.kapelusz.app.generated.band_deletion_keep
import com.jacekgajek.kapelusz.app.generated.delete_band
import com.jacekgajek.kapelusz.app.generated.delete_band_confirm
import com.jacekgajek.kapelusz.app.generated.delete_band_type_name
import io.kotest.core.spec.style.ExpectSpec

/** The owner asks to delete the band. The band stays for a week, with a notice for everyone and a way to keep it. */
class BandDeletedTest :
    ExpectSpec({
        e2e("a band set to be deleted tells its members so") {
            val alice = freshUser("Alice")
            val bob = freshUser("Bob")

            signIn(alice)
            createBand("Night Owls", "Alice")
            val code = inviteMember("Bob")
            signOut()

            signIn(bob)
            joinBand(code)
            see("Night Owls")
            signOut()

            // Deleting asks for the band's name, typed again.
            signIn(alice)
            openSettings()
            tap(Res.string.delete_band)
            type(text(Res.string.delete_band_type_name, "Night Owls"), "Night Owls")
            tap(Res.string.delete_band_confirm)
            see(Res.string.band_deletion_keep)
            wait(3, "the deletion is pushed to the server before signing out")
            signOut()

            // Bob is no leader, so he cannot keep the band. He is told who asked and when it goes. The notice is one
            // string with a name and a date in it, so a part of it is checked.
            signIn(bob)
            see("Night Owls")
            see("Alice asked to delete this band")
        }
    })
