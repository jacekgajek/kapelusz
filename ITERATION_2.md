# Art Split — Version 2 (app 1.0)

The second planning iteration. It ships as **app version 1.0.0** (`artsplit.versionName`), the first release called
stable. It builds on version 1 as described in `VERSION_1.md`; section references like "V1 §4.5" point there.

Constraints for this version:
- **Notifications are Android only** and come from the phone itself: no Firebase / FCM, no web push, no server push.
- **No new paid AWS services.** One new public server endpoint (the calendar feed); no other infrastructure changes.

---

## 1. Auto-update (Android)

The app keeps itself up to date without the Play Store.

- The background sync (§5.1) reads `/download/version.json` **once a day**.
- If a newer build is published, the app **downloads the APK itself** in the background (about 2.6 MB; mobile data allowed).
- **Install:**
  - **silently where Android allows it** (Android 12+, once the installed version was installed by Art Split itself, so Art Split is the "installer of record"); Android may still ask the first time;
  - otherwise a notification **"Art Split 1.0.1 is ready · Install"** opens Android's install screen.
- A silent install restarts the app, so it waits until the app is **not in the foreground**.
- **Once:** Android must allow "install unknown apps" for Art Split. The app explains why before opening that setting. The first update after installing from the browser therefore needs one confirmation; after that it should be hands-off.
- **Settings:** "Automatic updates" switch (on by default), the current version, and **Check now**.
- Requires the `REQUEST_INSTALL_PACKAGES` permission.
- The existing update strip (V1 §6.2) stays as the fallback when automatic updates are off.

## 2. Outdated apps

Version 1 apps fail to read new kinds of data (a new operation type in history, a new rejection reason). Version 2
makes this explicit, so the data format can change later without silently broken apps.

- Apps send their version code with every request as the query parameter `appVersion=<versionCode>` (0 for local development builds, which are always allowed). A header would have needed the API's CORS allow-list changed.
- The server has a **minimum supported version** in its code (`Api.MIN_APP_VERSION`, 0 = everyone), raised whenever a change would break older apps. Below it, or without a version (apps from before version 2), it answers `426` with `update_required`. Sync then stops retrying.
- The app then shows a blocking screen: **"This version of Art Split is too old. Update to continue"** with **Update** (Android: download and install, §1; web: reload).
- **Raising the minimum takes two deploys:** first deploy apps that handle the new data, then set the minimum to *that* deploy's run number. A number not yet deployed would refuse every app, and during a deploy the server switches before the new web app and APK are uploaded.
- History entries an app cannot read are shown as **"A change from a newer version"** instead of failing the whole page.
- Initial users are known personally, so the policy is pragmatic: raise the minimum when needed and tell people to update.

## 3. Event list

### 3.1 "Continue" card: unsettled business
- Shows the **most recent unsettled event**: not archived, and `OPEN`, `LOSS`, or a `DRAFT` whose date has passed (a past gig with no money entered yet). Future events do not count.
- Order: latest date first; among events on the same date, the one edited last.
- The same rule for everyone (not only events you take part in).
- **Hidden** when nothing is unsettled.

### 3.2 "Upcoming" card
- **"Upcoming: Club gig · Sat 12 Sep · arrival 17:00"**: the nearest non-archived event dated **today or later**, with its first agenda time (§4) if set. Tapping opens the event.
- The nearest event **you take part in**; if you take part in none, the nearest band event.
- Shown below the Continue card. Both are compact one-line cards; each is hidden when nothing matches. An event is never on both: an open gig tonight shows as Continue only.

### 3.3 Bulk archive (removed)
Removed in V2-M7: events are archived one at a time from the event's menu, and history undo (§7.2) covers mistakes.

## 4. Event agenda and details

### 4.1 Agenda times
- **Band template** (admins, Settings → **Event agenda**): the list of agenda times new events get. They can rename, reorder, add and remove entries. Default: **Arrival**, **Sound check**, **Concert start**.
- A new event **copies the template** as it is at that moment; changing the template later does not change existing events.
- **Per event**, leads add **one-off times** with their own label ("+ Add time", e.g. "Sound check 18:00", "Dinner 19:00").
- **Unset times as chips** ("+ Arrival", "+ Sound check", "+ Concert start", "+ Add time") below the set ones, in the create and the edit dialog (§4.3). Once a time is set it shows as a row, like address fields in Android Contacts.
- **After midnight:** times before **05:00** count as the next day and are shown with **(+1)**. Nobody arrives or does a sound check between midnight and 05:00.
- **Display:** the event header shows "Arrival 17:00 · Concert start 20:00"; event list rows show the first time next to the date.
- **Data:** each event stores its own list of *(id, label, time?)*; the band stores the template (labels in order). New operations set the template and an event's times.

### 4.2 Details (replaces notes)
- **Notes are removed** from the create and edit dialogs. Existing notes become the details (no migration needed).
- A **Details** section on the event screen, after the header:
  - keeps line breaks (a pasted setlist shows one song per line), collapsed after about 6 lines with **Show all**;
  - the text can be selected and copied;
  - edited **in place** with a pen button that opens a full-screen editor;
  - **leads and above** edit it, like the rest of the event.

### 4.3 Times when creating an event
- The create dialog has the same agenda section as the edit dialog: set times as rows (tap to change, ✕ to clear), and the unset ones as chips ("+ Arrival", "+ Sound check", "+ Concert start", "+ Add time"), shown directly. No "More details" in either dialog.

### 4.4 Event location on Google Maps
No in-app map (Google's map and place APIs need an API key and a billing account); Google Maps itself does the picking.
- An event has the readable **location** text and, separately, a **Maps link** (from Google Maps' Share).
- **Select on map** (next to the location field) opens Google Maps (the Maps app on Android, a new tab on the web) at the typed location. There you find the place or drop a pin, and *Share → Copy link*; **Paste from Maps** fills the field from the clipboard: a place name goes into the location if it is empty, the link is stored.
- **Share from Google Maps to Art Split** (Android): Art Split asks *"Set the location of…"* with the upcoming events the user may edit (nearest first) and **New event at this place** (the create dialog with the location filled in). If there is nothing to choose and the user cannot create events, it says so.
- **Navigate** on the event (next to the location, for everyone, when there is a location): opens the stored Maps link (the exact pin; directions are one tap away there), otherwise directions to the location text.
- Editing the location: leads and above, like the rest of the event.
- The calendar feed puts the Maps link into each entry; gig reminders with a location have a **Navigate** action.

## 5. Notifications (Android)

### 5.1 Background sync
- Periodic sync of **all** the user's bands (today only the open band is pulled, and only while the app is visible), every few hours, only with a network connection (Android WorkManager). Exact timing is up to Android.
- After each sync the app reschedules gig reminders (§5.2), looks for new transfers (§5.3) and, once a day, checks for updates (§1).
- Reminders are also rescheduled after a phone restart.

### 5.2 Gig reminders
- For the event's **participants** only. Scheduled **on the phone** from its offline data; no server involved.
- **Lead time in days:** any of **1, 2, 3, 7 days before** can be ticked (default: 1 day). **One time of day** for all of them (default **18:00**).
- Text: "Tomorrow: Club gig, arrival 17:00" / "In 3 days: Club gig (Sat 12 Sep)", using the first agenda time if set. Tapping opens the event.
- **Permission (Android 13+):** asked **once, the first time a sync finds an upcoming event you take part in**, with a personal dialog: **"Jacek added you to Club gig on 12 Sep. Get a reminder 1 day before?"** (Yes / Not now).
  - For that, each participant records **who added them**. For events you created yourself, or older events where it is unknown: "You're playing Club gig on 12 Sep. Get a reminder 1 day before?".
  - **Yes** opens Android's permission prompt. **Not now** turns reminders off; Settings → Notifications can turn them on again (asking again, or explaining the system setting).
  - Android 12 and older need no permission.

### 5.3 Transfer notifications
- When a sync finds a **new transfer involving you** (you send or receive it, or it involves the band fund and you hold it) that **someone else recorded** (Settle, Part…, Settle all, Hand over): **"Alice recorded 129,60 zł from Dave to you · Club gig"**.
- Never for your own actions.
- Several transfers recorded together are grouped per event: **"Club gig: 2 transfers recorded with you"**.
- Delayed until the next background sync; that is acceptable (even days would be).
- The first sync after installing or signing in only records what exists, so there is no flood of old transfers.

### 5.4 Settings → Notifications
- **Gig reminders** (on by default): lead days, time of day.
- **Transfer notifications** (on by default).
- Stored per device.

## 6. Calendar subscription

Gigs appear in Google Calendar (or any calendar app) through a subscription that stays up to date.

- **One private link per user:** Settings → **Calendar subscription** with **Copy link** and **Add to Google Calendar** (Google Calendar's "add by URL" page on the web; the `webcal://` link on Android).
- **Contents:** events from **all your bands** in which **you take part**, from **3 months back** onward. Archived events are left out. A switch **"Include events I don't take part in"** (off by default) adds the whole band's schedule.
- **Live:** the server builds the feed from the current band data on every fetch, so a gig a lead adds you to appears, and one you are removed from disappears, on the calendar's next refresh. **Google decides when it refreshes** (usually every few hours, sometimes up to a day); the app cannot speed that up.
- **Entry:**
  - title: event name; place: location;
  - **time:** no agenda time → **all-day**; one time → **one hour** starting at it; several → **earliest to latest** (times before 05:00 count as the next day). An entry that ends a bit early is acceptable;
  - description: agenda times, details (e.g. the setlist) and a link to the event.
- **Privacy:** the link carries a long random secret (calendars cannot sign in). **Reset link** makes a new one and kills the old. Entries carry no money.
- **Server:** `GET /calendar/{secret}.ics`, without authentication (the secret is the authorization). Stored as `users/{sub}-calendar.json` (the secret and the switch) and `calendar/{secret}.json` → user. A one-off "Add to calendar" button per event is not needed.

## 7. Small fixes, secret menu and undo
- Removing a participant who has collected money or transfers: the message says to remove all of their collected money and transfers first (moving the money records a transfer, which also blocks removal).
- History: a spinner instead of "…" while the event screen's history loads, and while **Load more** loads in the band history.

- Settings: **Delete band** sits at the very bottom, below Sign out, after a divider (only the version number is below it).
- Agenda template (Settings): a **Reset to defaults** link fills the editor with the built-in times (Arrival, Sound check, Concert start, in the app's language); nothing changes until **Save**, which then stores "the defaults", so built-in times added later apply too. The text fields get some space around them.
- Event screen, **Cost refunds**: a **+ Add cost** button (like "Add collected money") opens Add cost with **Incurred for** set to the event and **Paid by** set to me; saving returns to the event. Shown to whoever may add costs.

### 7.1 Secret menu (admins)
- In Settings, a **tap on the version number** opens the secret menu for an **Admin or Owner** of the open band; for others it does nothing.
- **Gig reminders on this phone** (Android): whether notifications are allowed, the battery optimisation state as information (optimised is Android's default and reminders arrive with it, also on realme's "intelligent" mode), a button to the app's Android settings, the next scheduled reminders, and **Test reminder in 1 minute** through the same alarm path (Android may deliver it up to an hour later).
- **Copy raw JSON data** copies the band's file exactly as stored in the bucket (`bands/<id>/state.json`, byte for byte), so putting the copy back changes nothing. A server endpoint for admins returns the stored file unchanged; offline it shows an error.

### 7.2 Undo from history
- In the **event's history** and the **band's history**, the **undo arrow (↶)** is on the **newest change that is not undone**: undo lines ("Undid: …") and the changes they undid (struck through, "undone") are skipped, so tapping again steps further back.
- The **redo arrow (↷)** is on the newest undo still in effect, unless a change was made after it (a new change ends redo, as in editors). Redoing shows as "Redid: …".
- Example, newest first: "C ↶ · Undid: B · ~~B~~ · A" (no redo: C came after the undo). Undoing C gives "Undid: C ↷ · ~~C~~ · Undid: B · ~~B~~ · A ↶".
- Undo puts back exactly what that change touched (as it was, including when an event was last changed). It is **refused** ("Changed since, can't undo") when any of those things changed afterwards.
- Tapping the arrow asks first, naming the change ("Undo 'Bob added cost Fuel 50 PLN'?").
- Undo is an ordinary change: the same permissions apply as for making the reverse change by hand, and the arrow is shown only to those who may. It appears in history as its own line ("Undid: …").
- Undoing a recorded transfer or a settlement needs a **lead** (as removing a transfer does), even when a member could record it.
- Undo is refused when the result would break the band's rules: the band fund in use without a treasurer, an archived treasurer, references to removed members or costs, a cost refunded more than its amount. Undoing a move is refused once the order changed again; things that come back return next to their old neighbours, and later moves stay.
- The band history offers undo and redo in the current month's view. Changes the user cannot undo are stepped over.
- Not undoable: joining through an invite, unlinking an account, transferring ownership, deleting the band.
- Each change records what it touched, before and after (in the history log, by the server); an undo names the log entry and the server takes the change from there. Changes from before V2-M7, and very large offline batches, have no such record and no arrow.

### 7.3 Settings layout
- The main Settings page is a list: icon, name, current value; entries that open a page have a ›. Grouped by whom they affect:
  - **Band** (for everyone): **Band name** (tap: rename dialog, admins), **Band fund** (page: what the fund is, on/off and who holds it; read-only for non-admins), **Event agenda** (page: the template, admins only).
  - **Personal**: **Language** (tap: dialog with the choices), **Notifications and calendar** (page: gig reminders, transfer notifications and the calendar subscription; on the web only **Calendar subscription**).
  - Then **App updates** (Android) and **Account** in place, and **Delete band** last after a divider; the version (secret menu) below.
- Pages are their own navigation entries, so Back returns to the list; on wide screens the content stays at a readable width.

### 7.4 Event polish and Google photos
- Event screen, **Settlement**: an information panel only (who gets or hands over how much); the per-person **Settle** button is gone, because it recorded several transfers at once. Transfers are recorded in **To settle** (each "X → Y" line, or Settle all).
- **To settle**: the "X → Y amount" lines in a smaller font, so longer names do not wrap.
- **Google photos** instead of initials for members with an account: in the event list, Band members, the participant chips on the event screen, and next to the account in Settings. Shown to bandmates automatically (no switch). Guests, members without an account, and photos that cannot be loaded show initials.
- The photo comes with the Google sign-in: the server keeps it with the user and puts it on their member in each band at sign-in and session renewal (about every 30 days) and when they join a band. Unlinking an account removes it. It is not a change in the history, and undo ignores it.

## 8. Later (not in 1.0)
- **Calendar view** of the event list (a month grid).
- **Facebook login**: needs a Facebook developer app in Live mode (privacy-policy and data-deletion pages), SDKs on both platforms and a server secret; logins would be separate users, not linked. **Sign-in by email link** (AWS SES) is the simpler alternative if people without Google accounts join; SES has no idle cost (about $0.10 per 1000 emails).
- **Instant notifications** through FCM, if the background-sync delay turns out to annoy.
- **Web push**, and iPhone (home-screen web app only).
- Play Store.

---

## 9. Milestones and task checklist

Workflow as in version 1: one branch + PR per milestone (the user creates the branches), checkboxes ticked in the
commit that completes them, task IDs in commit messages, plan changes in the decision log.

### V2-M1 — Auto-update
- [x] V2-M1.1 Background job: daily `version.json` check and APK download
- [x] V2-M1.2 Install through `PackageInstaller`: silent where allowed, otherwise an "Install" notification; never while the app is in the foreground
- [x] V2-M1.3 "Install unknown apps" explanation and permission flow
- [x] V2-M1.4 Settings: Automatic updates switch, current version, Check now
- [x] V2-M1.5 Version label in a corner of the sign-in screen and Settings (and `artSplitVersion` on the web), to check what a deploy put live

### V2-M2 — Foundations and quick wins
- [x] V2-M2.1 App version on every request, server minimum version, `426 update_required` and the "too old" screen
- [x] V2-M2.2 Unreadable history entries shown as "a change from a newer version"
- [x] V2-M2.3 History spinners (§7)
- [x] V2-M2.4 Continue card rule (§3.1) and Upcoming card (§3.2)
- [x] V2-M2.5 Bulk archive with selection mode (§3.3)

### V2-M3 — Event agenda and details
- [x] V2-M3.1 Model and operations: band agenda template, per-event times, who added each participant
- [x] V2-M3.2 Settings → Event agenda (admins)
- [x] V2-M3.3 Event times in the edit dialog behind More details, one-off times, after-midnight rule, display in header and list
- [x] V2-M3.4 Details section with full-screen editor; notes removed from the dialogs

### V2-M4 — Background sync and notifications
- [x] V2-M4.1 Periodic background sync of all bands (WorkManager), rescheduling after restart
- [x] V2-M4.2 Gig reminders and Settings → Notifications
- [x] V2-M4.3 Personal permission prompt (Android 13+)
- [x] V2-M4.4 Transfer notifications, grouped per event, with the first-sync baseline

### V2-M5 — Calendar subscription
- [x] V2-M5.1 Server: secret links, `GET /calendar/{secret}.ics`, reset
- [x] V2-M5.2 Feed contents and timing rules (§6)
- [x] V2-M5.3 Settings → Calendar subscription: copy, add to Google Calendar, include-all switch, reset

### V2-M6 — Times at creation and event locations
- [x] V2-M6.1 Agenda section shared by the create and edit dialogs; no "More details" (§4.3)
- [x] V2-M6.2 Maps link on events, Select on map, Paste from Maps (§4.4)
- [x] V2-M6.3 Share from Google Maps to Art Split (Android) with the "Set the location of…" picker and "New event at this place"
- [x] V2-M6.4 Navigate on the event, the Maps link in the calendar feed, Navigate on reminders

### V2-M7 — Secret menu, undo, no bulk archive
- [x] V2-M7.1 Secret menu with "Copy raw JSON data" (§7.1)
- [x] V2-M7.2 Undo from history (§7.2)
- [x] V2-M7.3 Remove bulk archive (§3.3)
- [x] V2-M7.4 Clearer message when a participant with collected money or transfers cannot be removed (§7)
- [x] V2-M7.5 Delete band at the bottom of Settings; agenda template Reset to defaults and roomier fields; + Add cost in the event's Cost refunds (§7)
- [x] V2-M7.6 Reminders scheduled again right away when settings or gigs change (not only when the app leaves the screen); reminder checks in the secret menu

### V2-M8 — Settings layout
- [x] V2-M8.1 Settings as a grouped list with pages for the band fund, event agenda, and notifications and calendar (§7.3)

### V2-M9 — Event polish and Google photos
- [ ] V2-M9.1 Settlement panel without Settle buttons; smaller To settle lines (§7.4)
- [ ] V2-M9.2 Google photos of members with an account (§7.4)

### V2-M10 — Release 1.0
- [ ] V2-M10.1 Release checklist (§10) passed on real phones
- [ ] V2-M10.2 `artsplit.versionName=1.0.0`, deploy, GitHub Release

## 10. Release checklist (by hand, on real phones)
1. CI deploy green, including the APK step (keystore secret set from the file).
2. A real invite between two Google accounts: the shared link opens the Android app (App Link), the invitee joins, both see the same band.
3. Attachments: a photo added on one phone opens on another.
4. Sessions: the app is used across more than an hour with no account picker, and stays signed in for days.
5. Auto-update: build N installed from the website, N+1 published, the phone updates itself.
6. Reminders arrive N days before at the set time, also after a phone restart.
7. The calendar subscription shows a gig in Google Calendar, including after a lead adds you to one.
8. Delete band on a throwaway band: the other member's app says it was deleted.
9. A place shared from Google Maps to Art Split; Navigate on the event and on its reminder.
10. Undo from history on a real change, and its redo.

Monitoring stays as in version 1: server logs in CloudWatch, no crash-reporting service.

## 11. Decision log
| Date | Decision |
|---|---|
| 2026-10-21 | Version 2 refined from the draft through a Q&A session. It ships as app 1.0.0. |
| 2026-10-21 | Notifications are Android only and generated on the phone (reminders scheduled locally, transfers found by background sync). No FCM or Firebase in 1.0: transfer notifications are not urgent, and it keeps 1.0 free of a server key and device-token handling. |
| 2026-10-21 | Gig reminders only (no "new event" notifications), for participants, a number of days before at a chosen time of day. Notification permission is asked when the first upcoming gig appears, naming who added you. |
| 2026-10-21 | Auto-update is fully automatic where Android allows it; it never restarts the app in the foreground. It comes first (V2-M1). |
| 2026-10-21 | Outdated apps are stopped explicitly with "update required" from a server-side minimum version, instead of failing on data they cannot read. |
| 2026-10-21 | The "Planned" filter and the calendar view were dropped in favour of an "Upcoming" card; the calendar view may come later. |
| 2026-10-21 | Agenda: a band template of times (default Arrival, Sound check, Concert start), copied into each new event, plus one-off times per event. Calendar entries run from the earliest to the latest time (one hour for a single time, all-day without times); times before 05:00 are the next day. Free text goes into a Details section that replaces notes. |
| 2026-10-21 | Google Calendar through a per-user subscription link rather than a one-off button or the Calendar API (which needs a sensitive scope and Google's app verification). Refresh timing is Google's. |
| 2026-10-21 | V2-M1 review: a downloaded update installs **30 minutes** after the app left the screen (or with the daily check while it is hidden), so stepping out briefly, e.g. to take a photo for a cost, never restarts it. A declined or failed version, or one Android asked to confirm, is not tried again on its own; Settings offers "Install now", and the strip offers a pending confirmation. The "ready to install" notification needs the notification permission, which the app asks for with gig reminders (V2-M4); until then the strip in the app is the way. |
| 2026-10-21 | V2-M2: the app version goes as the query parameter `appVersion`, because a new header would need the API's CORS allow-list (terraform) changed. On the web, the browser's Back button goes back a page rather than leaving selection mode (a page cannot intercept it); ✕ in the top bar leaves it. On Android, Back leaves selection mode. |
| 2026-10-21 | V2-M3 review: a band stored by an older app (which dropped the new fields but kept the server's version) is downloaded in full once (`LocalBand.schema`), so agenda times are never shown missing and then overwritten. Unknown enum values from newer versions fall back to defaults (`coerceInputValues`). Template entries are marked `fromTemplate` instead of being recognised by label; the template editor keeps whole entries, so moving or removing one never turns built-in times into fixed labels. |
| 2026-10-21 | `MIN_APP_VERSION` raised to 11 (the V2-M2 deploy, already live) together with V2-M3: older apps cannot read the history entries of agenda changes, and build-11 apps read history leniently and show "update required". Build 10 updates itself (V2-M1). |
| 2026-10-21 | V2-M4: reminders are inexact alarms (no exact-alarm permission): from Android 12 they arrive within about an hour of the set time, before that within a 15-minute window; the background sync runs every 4 hours with a connection. Transfers already on the device when the app leaves the screen count as seen (the user had the app open), so only later ones are notified. |
| 2026-10-21 | Sound check (Próba dźwięku) is a third built-in agenda time and part of the default template. Builds before 12 cannot read an unknown kind of time, so `MIN_APP_VERSION` is 12 (live since V2-M3). |
| 2026-10-21 | Event locations without an API key or billing: Google Maps does the picking (Select on map, Paste from Maps, or share from Maps to Art Split on Android), the event stores the Maps link, and Navigate opens it. Times can be set when creating an event, and the unset ones are shown as chips directly. |
| 2026-10-21 | Facebook login deferred; email-link sign-in (SES) noted as the cheaper alternative if needed. |
| 2026-10-22 | V2-M7: bulk archive removed. Undo from history: the newest line of the event or band history, refused when what it touched changed since; permissions as for the reverse change; confirmed in a dialog; undo of an undo redoes. Membership and ownership changes and band deletion are not undoable. The secret menu (one tap on the version, admins) copies the band file exactly as stored in the bucket. The Continue card rule stays: future events with money on them do not count. Undo steps back through changes (the arrow skips undone changes and undo lines) with redo for the newest undo until a new change is made; the first idea (only the newest line, undoing an undo redoes) could never go back more than one step. |
| 2026-10-22 | Reminders: a changed reminder time or gig was only scheduled when the app left the screen (through a deferrable background job); now it is scheduled at once. Alarms stay inexact (no exact-alarm permission), so from Android 12 a reminder may come up to an hour late. The secret menu shows reminder checks and sends a test reminder. |
| 2026-10-22 | No battery-optimisation warning or "run in the background" prompt for users: reminders arrived on a realme GT 7 (Android 16) with the default "intelligent" battery mode. |
| 2026-10-22 | History performance options (pooled S3 client, DynamoDB for the log, more Lambda memory) moved to VERSION_3.md; history stays as it is for 1.0. Google photos for members with an account, shown automatically, refreshed at sign-in and session renewal. The per-person Settle button in the event's Settlement panel is removed. |
