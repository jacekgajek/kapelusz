# Kapelusz — Iteration 4

Defined 2026-10-23 (grilling). Same loop as before: a PR per milestone; branch, implement, review, fix, push, merge,
smoke test.

## 1. V4-M1

### 1.1 Artist role

A new role, **Artist / Artysta**, for people who play with the band (often contractors) and should see only what
concerns them.

- **Place in the roles:** below Member: Viewer, Artist, Member, Lead, Admin, Owner. Viewer still sees everything and
  changes nothing; Artist sees less but adds their own costs. So it is not a strict ladder: the visibility rules are
  Artist-specific, not "at least Viewer".
- **Not the default:** the invite's role picker still starts on Member, listed as Viewer, Artist, Member, Lead,
  Admin (changed after the grilling, 2026-10-23). Existing members keep their roles.
- **Viewer stays, set apart:** it sees everything and changes nothing (a manager, an accountant), so it is not a step
  below Artist. The picker lists Artist, Member, Lead, Admin, then "Read-only": Viewer; the descriptions no longer say
  "everything a viewer can".
- **What they see (UI only in M1, see §1.4):**
  - Tabs: **Events** and **My costs**, plus Settings (notifications, calendar, account). No Band fund, History, Roster
    or the band-wide settle screen.
  - Events: only those a lead added them to (upcoming and past, not archived). On an event: name, date, location and
    map link, agenda times, and the names of who plays. No weights, shares, income, collected money, costs, refunds of
    others, band fund or suggested transfers.
  - Their own settlement card per event: their share + their refunds = **to receive**, received so far, **still to
    receive**. Never who pays them.
  - My costs: their own costs and their statuses (§1.2).
  - Calendar: only their events; "Include events I don't take part in" is hidden, and the server's feed ignores it for
    Artists.
- **What they do:**
  - Add, edit and delete their own costs, like members: with or without an event; when with an event, only one they
    take part in (already the rule for everyone). Their costs are never included automatically (§1.2).
  - No collected money and no transfers: they cannot record either, and see no "Settle" actions. The payer or a lead
    records transfers to them.
- **Artists never hold money:**
  - Collected money cannot be recorded on an Artist's line by anyone, leads included (reducer rejection).
  - An Artist cannot be the band fund holder.
  - So an Artist only ever receives money, and gets no debt reminders (they are only for the one who pays).
  - Making someone an Artist (role change, or an invite accepted for their roster entry) is refused while they hold
    collected money in an unsettled event or hold the band fund: "settle their events / hand over the band fund
    first". Settled events don't matter. An artist invite for someone holding money is refused when created, and when accepted (if
  they got money meanwhile) the invitee is told to have it settled first; the invite stays.
- **Notifications:** gig reminders and calendar for their events, as for members. No "transfer recorded with you"
  notification that would name the sender.

### 1.2 Costs wait for a lead: Include or Refuse

Today a member's cost for an event they play is included in its refunds automatically while the event has no
transfers; only costs from elsewhere, or added after money moved, wait in "Pending from earlier" for a lead's Include.

- **No auto-include for non-leads:** a cost added or edited by a Member or Artist always waits for a lead.
  - A cost entered by a lead (their own or on someone's behalf) for an open event is still included automatically.
  - Creating an event or adding a participant no longer includes anyone's costs.
- **The event's "Pending from earlier" section is renamed "Waiting" / "Oczekujące"**: it now holds every cost that
  waits for a lead, this event's ones included.
- **Refuse:** next to **Include** in the Waiting section (only there). Refusing marks the cost **Refused**:
  - it leaves the Waiting section of every event;
  - it is final: a lead cannot include it again; only the payer can resubmit it (below).
- **Statuses the payer sees in My costs:**
  - **Waiting / Oczekuje**: replaces both Pending and Refund planned.
  - Partly refunded, Refunded, as today.
  - **Refused / Odrzucony**: in red, with a one-tap delete icon.
- **Editing a refused cost:** tapping edit first asks "This cost was refused. What do you want to do?" with three
  options:
  - **Edit and resubmit**: opens the editor; saving turns it back into Waiting;
  - **Delete**;
  - **Nothing**.
- No reason and no notification for a refusal (only the red status).
- A member's or artist's edit never raises an included refund (lowering it still follows the cost); the raise waits for
  a lead in the event's Waiting list, where Include tops the refund up.
- Deleting a refused cost from My costs asks for confirmation (artists have no History to undo it).
- An artist can still end up owing (overpaid, an event reopened after they became one, money from before): a **lead**
  settles it, by "Settle all" or by recording the transfer from the artist; the artist cannot record it. Their payout
  card then says "To hand back (a lead settles it)" without naming whom. Undo no longer guards "artists hold no money"
  (ordinary edits don't either). (Changed 2026-10-23.) Resubmitting is explicit (`UpdateCost.resubmit`), so History says "resubmitted".
- Undo cannot bring back a refund of a refused cost or money held by an artist.
- Refuse is offered only for a cost with nothing refunded yet (anywhere, the band fund included); a partly refunded
  cost can still be included, not refused.
- A refused cost shows under My costs' "Pending" filter (the default), so its owner sees it.
- History shows "refused" and "resubmitted"; Undo works as for other ops.

### 1.3 Notifications

- **Defaults:** only the reminder about unsettled business (open debts) is on. Gig reminders and "transfers others
  record with me" start off.
- The one-time "remind me before gigs?" question (Android 13+) still comes with the first upcoming gig: it is now how gig
  reminders get turned on, and the permission it asks for also lets the debt reminders through.
- **Debt reminders every two weeks:** 14, 28, 42 and 56 days after the gig (still at most four), instead of 14, 21, 28
  and 35.

### 1.4 Note: making the Artist's privacy server-side (not in M1)

In M1 an Artist's device still downloads the whole band (state, op log, history) and only the screens hide it. Anyone
technical, or a future UI bug, can see everything. Making it real privacy means the server sends an Artist only what
they may see. Rough effort: **one milestone of about V3-M2's size**, mostly server and sync. What it involves:

- **Redacted state:** `/bands/{id}/state` for an Artist builds a projection:
  - only their events, with participants' names but without weights, fixed shares, holdings, others' refunds,
    transfers between others or the fund amount;
  - only their own costs;
  - no fund entries;
  - a roster of names only.
  - Their numbers (to receive, received) come precomputed by the server, because the full settlement cannot be computed
    from the projection. The ETag is per projection.
- **Op log:** clients catch up by replaying ops. For an Artist the server must filter ops (only theirs and their
  events' details) or send a fresh projection instead of ops. The second is simpler: Artists sync by snapshot only.
- **Client sync on partial data:** the local optimistic apply (shared Reducer) runs on the projection.
  - Their own cost ops validate fine there.
  - The money views must use the server's numbers, not `SettlementCalculator`.
  - The SyncEngine needs a "snapshot-only" mode, and Undo/History must work without the full band.
- **History endpoint:** filtered to the Artist's own costs and their events' details, or not offered (M1 already hides
  it).
- **Attachments:** download allowed only for the Artist's own costs (today any band member may fetch any receipt).
- **Calendar feed and invites:** the feed is already per user (just enforce "only my events"); invite listing is already
  admin-only.
- **Tests:** a projection test per op type, so that nothing leaks through a new field later. Leaks are the main risk:
  every future model field needs a "visible to Artists?" decision.

## 2. V4-M2

### 2.1 Files on collected money and on events

Decided 2026-10-23 (grilling). A contract, a rider, a stage plan: files belong to the money they explain, or to the gig.

- **Two places:**
  - **A collected entry** (e.g. "Contract 2000 zł, held by Alice") gets up to **5 files**, like a cost's receipts.
  - **The event** gets a **Files** list of up to **10 files**, not tied to any money (contract, rider, stage plan,
    setlist).
- **Kinds and sizes as receipts:** photos (compressed on the device to JPEG) or PDFs up to 10 MB. The file name is shown
  and can be renamed (receipts too).
- **Who adds and removes:**
  - entry files: whoever may edit that entry (its holder for their own, leads for any);
  - event files: leads and up, like editing the event;
  - archived events are read-only, as now.
- **Who sees:** everyone who sees the money sees entry files; Artists don't (like the money). Event files are seen by
  everyone, Artists included (a rider or setlist is useful to them).
- **History and undo:** adding, removing and renaming show in History; undo as for receipts (a removed file cannot come
  back once its file is deleted).

## 3. Later

- Server-side privacy for Artists (§1.4).

### Integrations with other systems (proposals)

Collected 2026-10-23. Fees are **who pays what to the provider**:
- **Initial:** a one-off setup or activation fee.
- **Idle:** a recurring fee even when nothing is used (monthly or yearly).
- **Per use:** a fee per transaction, message or call.

"App" means it is paid by whoever runs Kapelusz (today: the AWS account); "band" means each band pays with its own
account. Amounts are approximate, from public pages and third-party summaries as of October 2026: check them before
choosing.

| Integration | What it gives | Initial | Idle | Per use | Who pays / notes |
|---|---|---|---|---|---|
| **Bank transfer QR / payment details** (Polish "Rekomendacja ZBP" QR, EPC QR for SEPA) | "Pay" on a suggested transfer shows a QR the bank app scans, with amount and title filled in | none | none | none | No provider: the app draws the QR. Needs each member's account number stored. |
| **BLIK / Revolut / PayPal.me links** | Open a payment to a member from the transfer list | none | none | none | Personal links only; no confirmation back to the app. |
| **Payment gateway** (Stripe, Przelewy24, PayU, Tpay) | Pay transfers by card/BLIK in the app; recorded automatically | Stripe: none. P24: ~49 zł activation via some channels | Stripe: none. P24: none, or ~50 zł/month for lower BLIK rates | Stripe PL ~1.5% + 1 zł per card payment. P24 ~1–1.6% + ~0.30 zł | Band (or each member) as a merchant: needs a business account. The app gets webhooks. The heaviest legally (payment flows). |
| **Bank account data (PSD2)** (GoCardless Bank Account Data, ex-Nordigen; Kontomatik; Salt Edge) | Match incoming bank transfers to suggested ones, and mark them settled | usually none | free tier for a few connections (GoCardless, to verify); commercial providers monthly | per connection / per call on paid plans | App. Each member links their bank (consent renews every 90–180 days). |
| **Invoicing** (Fakturownia, inFakt, wFirma) | Issue the contract invoice from an event; its total becomes collected money | none | the band's own subscription (free tiers have a few documents a month) | none beyond the plan | Band, with its own API key. Only for bands with a business. |
| **KSeF** (Polish national e-invoice system) | Fetch invoices for the band's contracts / send them | none | none | none | Free government API, but complex: certificates and an XML schema. Only for bands that invoice as a business. |
| **E-signature** (Autenti, DocuSign, Yousign) | Sign a gig contract from the event's Files | none | subscription (per month, per sender) | per envelope on some plans | Band. Overlaps with the new event Files (§2.1). |
| **Google Drive / Dropbox backup** | Copy receipts and event files, plus a CSV of the money, to the band's own drive | none | none | none | Free APIs (quotas). Stored in the band's own account. |
| **Google Sheets export** | A live sheet of events, settlements and costs for the band's accountant | none | none | none | Free API (quotas). |
| **Accounting CSV / JPK-style export** | Download for an accountant | none | none | none | No provider. |
| **Email notifications** (Amazon SES) | Reminders and "transfer recorded" by email, for web and iPhone users without push | none | none | ~$0.10 per 1,000 emails | App. Needs a verified sending domain. |
| **SMS reminders** (SMSAPI, OVHcloud SMS, Twilio) | Debt or gig reminders by SMS | none | none (prepaid packages) | ~0.11–0.14 zł per SMS in Poland (OVHcloud packages); Twilio more | App or band. Needs phone numbers and consent. |
| **Web push** (browsers, iPhone home-screen apps) | Notifications in the web app / installed PWA | none | none | none | Free (browser push services). Needs a push sender on the server. |
| **WhatsApp Business / Messenger** | Send a settlement or reminder into the band's chat | WhatsApp: business verification | none | WhatsApp: per conversation/template message | Band or app. Today's Share already covers most of this for free. |
| **Receipt OCR** (Google Cloud Vision, Amazon Textract, an LLM API) | Fill in amount, date and category from a receipt photo | none | none | ~$1.50 per 1,000 images (Vision text detection, with a free monthly allowance); an LLM call per receipt | App. |
| **Place search / autocomplete** (Google Places API New) | Type a venue, get its address and map pin | none | none (free caps per SKU: ~10k Essentials / 5k Pro events a month) | beyond the caps, ~$3–$40 per 1,000 depending on the fields | App. Paste from Maps stays free. |
| **Exchange rates** (NBP API) | Gigs paid in EUR: convert at the official rate on the gig date | none | none | none | Free public API. |
| **Gig listings** (Bandsintown for Artists, Songkick) | Import upcoming gigs as events | none | none | none | Access needs approval; terms limit use. |
| **Sign in with Apple** | Needed for a native iPhone app (App Store rule when Google sign-in is offered) | none | Apple Developer Program, $99/year | none | App. Only with a native iOS app. |

Sources (for the figures above; re-check before deciding):
- Stripe Poland rates: [Oliver Pay, Poland](https://oliverpos.com/en-sg/payments/poland/)
- Stripe, no monthly fee: [Activepieces, Stripe pricing 2026](https://www.activepieces.com/blog/stripe-pricing-2026-total-transaction-add-on-costs.md)
- Przelewy24: [Shoper / Przelewy24](https://www.shoper.pl/przelewy24), [freenance.io: P24 vs Tpay](https://freenance.io/przedsiebiorczosc/przelewy24-vs-tpay-2026-porownanie-bramka-platnosci-prowizje-rejestracja/)
- SMS: [OVHcloud SMS prices](https://www.ovhcloud.com/pl/sms/prices/)
- Google Maps caps: [Woosmap, Google Maps API pricing 2026](https://www.woosmap.com/blog/google-maps-api-pricing-breakdown), [Open Places API, Places pricing](https://openplacesapi.com/blog/google-places-api-pricing)
- GoCardless Bank Account Data: not confirmed; check the official page.

## 4. Milestones and task checklist

### V4-M1 — Artists, refusing costs, quieter notifications
- [x] V4-M1.1 Artist role: in the invite picker (Member stays the default), limited screens and event view, own settlement card, no holdings, role
  change guard (§1.1)
- [x] V4-M1.2 No auto-include for non-leads; "Pending from earlier" renamed Waiting; Refuse; Waiting/Refused
  statuses; one-tap delete; refused-edit dialog (§1.2)
- [x] V4-M1.3 Notification defaults; debt reminders every two weeks (§1.3)
- [x] V4-M1.4 Note on server-side privacy written (§1.4)

### V4-M2 — Files on collected money and events
- [x] V4-M2.1 Files on collected entries and on events, renaming files (§2.1)

## 5. Decision log

| Date | Decision |
|---|---|
| 2026-10-23 | Artist role (invites still default to Member, changed after the grilling); sees only their events (details + lineup), their costs and their own to-receive sum, never who pays. Events + My costs only. UI-only privacy in M1; the server-side effort is described in §1.4. |
| 2026-10-23 | Artists may add costs with or without an event, never hold money (nobody can record collected money for them, they can't hold the band fund), can't record transfers. Making someone an Artist is refused while they hold money in an unsettled event or the band fund. |
| 2026-10-23 | Costs of non-leads are never auto-included; a lead's cost entry still is; creating an event or adding a participant includes nothing. The event's "Pending from earlier" section becomes "Waiting" / "Oczekujące". Refuse only there; Refused is final for leads; the payer can resubmit by editing (dialog: Edit and resubmit / Delete / Nothing) or delete with one tap. No reason, no notification. Pending and Refund planned shown as "Waiting". |
| 2026-10-23 | Implementation: Refuse only for a cost with nothing refunded; server-internal ops count as a lead's for auto-include; the gig-reminder question stays (it turns them on). |
| 2026-10-23 | Viewer kept as read-only access to everything, shown apart below the ladder in the role picker. |
| 2026-10-23 | Notifications: only debt reminders on by default; debt reminders at 14/28/42/56 days. Artists get no debt reminders (they never owe). |
| 2026-10-23 | Files on collected entries (5) and on events (10), kinds as receipts, renamable; entry files follow who may edit the entry and are hidden from artists; event files by leads, seen by all. Integration proposals with their fees listed under Later. |
