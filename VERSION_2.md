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

- Apps send their version code with every request: `X-Art-Split-Version: <versionCode>`.
- The server has a **minimum supported version** in its code, raised whenever a change would break older apps. Below it, it answers `426` with `update_required`.
- The app then shows a blocking screen: **"This version of Art Split is too old. Update to continue"** with **Update** (Android: download and install, §1; web: reload).
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
- Shown below the Continue card. Both are compact one-line cards; each is hidden when nothing matches.

### 3.3 Bulk archive
- **Leads and above** only (the same people who archive single events); for others long-press does nothing.
- **Phone:** long-press an event to enter selection mode. **Web:** press-and-hold too, plus a **Select** entry in the top bar.
- In selection mode tapping toggles events. The top bar shows **"3 selected"**, **Select all** (the events currently shown) and **Archive**, or **Unarchive** when only archived events are selected. Back or ✕ leaves selection mode.
- No confirmation (archiving is reversible); a snackbar says **"3 events archived · Undo"**.
- No bulk delete: deleting has per-event rules and is about money records.

## 4. Event agenda and details

### 4.1 Agenda times
- **Band template** (admins, Settings → **Event agenda**): the list of agenda times new events get. They can rename, reorder, add and remove entries. Default: **Arrival**, **Concert start**.
- A new event **copies the template** as it is at that moment; changing the template later does not change existing events.
- **Per event**, leads add **one-off times** with their own label ("+ Add time", e.g. "Sound check 18:00", "Dinner 19:00").
- **Hidden until set:** unset times sit behind **More details** in the edit dialog ("+ Arrival", "+ Concert start", …). Once a time is set it always shows, like address fields in Android Contacts.
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

## 7. Small fixes
- History: a spinner instead of "…" while the event screen's history loads, and while **Load more** loads in the band history.

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
- [ ] V2-M2.1 Version header, server minimum version, `426 update_required` and the "too old" screen
- [ ] V2-M2.2 Unreadable history entries shown as "a change from a newer version"
- [ ] V2-M2.3 History spinners (§7)
- [ ] V2-M2.4 Continue card rule (§3.1) and Upcoming card (§3.2)
- [ ] V2-M2.5 Bulk archive with selection mode (§3.3)

### V2-M3 — Event agenda and details
- [ ] V2-M3.1 Model and operations: band agenda template, per-event times, who added each participant
- [ ] V2-M3.2 Settings → Event agenda (admins)
- [ ] V2-M3.3 Event times in the edit dialog behind More details, one-off times, after-midnight rule, display in header and list
- [ ] V2-M3.4 Details section with full-screen editor; notes removed from the dialogs

### V2-M4 — Background sync and notifications
- [ ] V2-M4.1 Periodic background sync of all bands (WorkManager), rescheduling after restart
- [ ] V2-M4.2 Gig reminders and Settings → Notifications
- [ ] V2-M4.3 Personal permission prompt (Android 13+)
- [ ] V2-M4.4 Transfer notifications, grouped per event, with the first-sync baseline

### V2-M5 — Calendar subscription
- [ ] V2-M5.1 Server: secret links, `GET /calendar/{secret}.ics`, reset
- [ ] V2-M5.2 Feed contents and timing rules (§6)
- [ ] V2-M5.3 Settings → Calendar subscription: copy, add to Google Calendar, include-all switch, reset

### V2-M6 — Release 1.0
- [ ] V2-M6.1 Release checklist (§10) passed on real phones
- [ ] V2-M6.2 `artsplit.versionName=1.0.0`, deploy, GitHub Release

## 10. Release checklist (by hand, on real phones)
1. CI deploy green, including the APK step (keystore secret set from the file).
2. A real invite between two Google accounts: the shared link opens the Android app (App Link), the invitee joins, both see the same band.
3. Attachments: a photo added on one phone opens on another.
4. Sessions: the app is used across more than an hour with no account picker, and stays signed in for days.
5. Auto-update: build N installed from the website, N+1 published, the phone updates itself.
6. Reminders arrive N days before at the set time, also after a phone restart.
7. The calendar subscription shows a gig in Google Calendar, including after a lead adds you to one.
8. Delete band on a throwaway band: the other member's app says it was deleted.

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
| 2026-10-21 | Agenda: a band template of times (default Arrival, Concert start), copied into each new event, plus one-off times per event. Calendar entries run from the earliest to the latest time (one hour for a single time, all-day without times); times before 05:00 are the next day. Free text goes into a Details section that replaces notes. |
| 2026-10-21 | Google Calendar through a per-user subscription link rather than a one-off button or the Calendar API (which needs a sensitive scope and Google's app verification). Refresh timing is Google's. |
| 2026-10-21 | Facebook login deferred; email-link sign-in (SES) noted as the cheaper alternative if needed. |
