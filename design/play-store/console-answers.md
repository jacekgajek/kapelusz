# Play Console answers (ITERATION_7 §1.4)

Drafted from the code on 2026-10-27, for Policy › App content. Check them against the forms: their wording changes, and
some choices (marked "check") depend on what the form offers.

## Privacy policy

https://kapelusz.jacekgajek.com/privacy-policy

## Ads

No, the app has no ads.

## App access

**All or some functionality is restricted.** Instructions:

> Sign in with any Google account; no special account is needed. Then tap "+ New band" (or "Create band"), enter a
> band name and your name. As the band's owner you can use everything: add events (+), members (⚙ › Band members),
> costs (My costs), money collected at an event (the event's Money tab), and see the settlement (Settle).

## Content rating (IARC questionnaire)

- Category: **Utility, Productivity, Communication or Other**.
- Violence, fear, sexuality, language, controlled substances, crude humour: **No**.
- Gambling or simulated gambling: **No**.
- Users interact or exchange content: **Yes** (check). Band members see each other's entries in a band they were
  invited to; there is no chat and nothing is public. If the form separates "shares content with other users" from
  "chat", answer Yes / No.
- Shares the user's current location with others: **No** (event places are typed or picked on a map; the device's
  location is never read).
- Digital purchases: **No**.
- Expected result: Everyone / PEGI 3 / USK 0, with "Users Interact" if answered Yes above.

## Target audience and content

- Target age: **18 and over** only (Q12).
- Appeals to children: **No**.

## News app

No.

## Financial features

The declaration lists kinds of financial services. Kapelusz moves no money, gives no loans, holds no accounts and
trades nothing: it records amounts that people hand over in person or by their own bank's BLIK. Answer **"My app doesn't
provide any financial features"**, or, if the form offers it, a personal finance / budgeting kind that needs no licence
(check).

## Government apps, health, COVID-19

No.

## Data safety

**Data collection and security**

- Does the app collect or share user data? **Yes** (collect; nothing is shared).
- Is all collected data encrypted in transit? **Yes** (HTTPS only).
- Can users request that their data be deleted? **Yes**: in the app (Settings › Account › Leave band, ITERATION_6
  §11a), and by email for who can't sign in. Delete-account URL: https://kapelusz.jacekgajek.com/privacy-policy#rights

**Shared:** nothing. Google (sign-in, places, maps, QR scanner) and Amazon Web Services (hosting) act as service
providers, which Play doesn't count as sharing.

**Collected** (all: not shared; processed for **App functionality**, and account items also for **Account
management**; none for analytics, ads or personalisation):

| Play's data type | What it is in Kapelusz | Required or optional | Ephemeral |
|---|---|---|---|
| Personal info › Name | Google account name (sign-in); member names entered in bands | Required | No |
| Personal info › Email address | Google account email: in the session, shown in Settings, not stored on the server | Required | Yes (check: it is kept in the device's session token) |
| Personal info › User IDs | Google account identifier | Required | No |
| Personal info › Phone number | BLIK phone number, if the member enters one | Optional | No |
| Financial info › Other financial info | amounts collected, costs, refunds, transfers, the band fund | Required | No |
| Photos and videos › Photos | receipt photos attached to costs, money or events; the Google profile photo link | Optional | No |
| Files and docs | PDFs attached to costs, money or events | Optional | No |
| App activity › Other user-generated content | events (name, place, schedule, notes), cost descriptions, band name | Required | No |
| Device or other IDs | a random identifier per band and installation, which orders a device's changes | Required | No |

**Not collected:** location (the device's), contacts, messages, audio, health, web browsing, app interactions,
crash logs, diagnostics, advertising IDs.
