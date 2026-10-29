# Privacy Policy — Pass the Hat (Kapelusz)

Effective date: 29 October 2026

This policy explains what personal data the Pass the Hat app (called Kapelusz in Polish; "the app") processes, why,
where it is kept, who receives it and what rights you have. It covers the Android app, the web app at
https://kapelusz.jacekgajek.com and the server behind them. The app settles a band's money after gigs; a band's data
is shared by its members and synchronised through the app's server.

## 1. Who is responsible

The data controller is Jacek Gajek, a private individual who develops and runs the app.
Contact for anything about privacy: **privacy@jacekgajek.com**.

## 2. Data we process

### 2.1 Your Google account

You sign in with Google. From Google we receive your account identifier, your name, your email address and the link to
your profile photo.

- The **account identifier** is stored on the server as your user record, together with the list of bands you belong
  to, the time of your last "Sign out on all devices", and the link to your profile photo.
- Your **name and email** are not stored on the server. They are kept in the session token held by your device and
  are shown to you in the app.
- Your **profile photo link** is copied to your member entry in each of your bands, so your bandmates see your photo.
  The photo itself stays on Google's servers and is loaded from there.

### 2.2 Band data

Everything you and your bandmates enter into a band:

- the band's name, currency and settings;
- members: their names (as entered by a band leader, not necessarily the Google name), roles, default shares, and,
  if a member enters them, a **phone number for BLIK** payments and a **bank account number**. Only the member enters
  their own; the app shows them only to whoever pays that member, and they are not recorded in the band's history;
- guests: names entered for a single event;
- events: name, date, location, agenda, notes, participants and their shares;
- money: amounts collected, costs and their descriptions and categories, refunds, transfers between people, and the
  band fund, with the **BLIK phone number and bank account number to pay into it**, if the fund's holder or a leader
  enters them (shown to the band's members, and recorded in the band's history);
- **attachments**: photos and PDFs (e.g. receipts) added to costs, received money or events;
- the band's **history**: each change, who made it and when, so it can be reviewed and undone.

Band data is visible to the band's members according to their role. Members with the Artist role see only their own
events, their own money and other members' names.

### 2.3 Other data the server keeps

- **Invites**: invite codes with the role and the member they are for, valid until they expire.
- **Calendar subscription**: if you turn it on, a secret link to a calendar feed of your band's events, and your choice
  of which events it includes.
- **Failed invite code attempts** in the current hour, to stop codes from being guessed.
- **Your Android devices, for sync messages**: while you are signed in to the Android app and in a band, a random
  identifier of the app's installation and its Firebase Cloud Messaging token, with the date it last checked in. The
  server uses them to tell your devices that a band changed, so they fetch it at once.
- **Event links** (`/e/…`): a pointer from an event's identifier to its band, so a link opens the right event.

### 2.4 Technical data

- The server logs one line per request: the method, the path (with invite codes and calendar links removed), the result
  and how long it took, plus error details when something fails. Logs do not contain the content of your band data.
- Our hosting provider (Amazon Web Services) processes your IP address and similar connection data to deliver requests.

### 2.5 What we do not collect

No advertising, no analytics, no tracking across apps or websites, no crash reporting service. The app does not read
your device's location, contacts or files other than those you pick or share to it. Photos are taken by your camera app
and only those you choose are attached. Google's map and QR code scanner on Android (see 4) send Google technical
data about their own use, such as device and performance information, under Google's privacy policy; we receive none
of it.

## 3. Why we process it (legal basis)

- **To provide the app** you signed up for — your account, your bands and their synchronisation, attachments, invites,
  calendar feeds and links (Art. 6(1)(b) GDPR, performance of a contract).
- **To keep the service secure and working** — sessions, limits on invite guesses, request logs (Art. 6(1)(f) GDPR,
  legitimate interest in a secure, reliable service).

Your data is not used for advertising, profiling or automated decisions, and is not sold.

## 4. Who receives the data

- **Your bandmates**, as described in 2.2.
- **Anyone you give a link to:**
  - an event link shows the event's name and date to anyone who has it, and opens the event itself only for the band's
    members;
  - a calendar feed link shows the band's events (names, dates, locations, agendas) to anyone who has it, until you turn
    the subscription off or create a new link;
  - an invite link lets the person who has it join the band in the role it was made for, until it expires.
- **Amazon Web Services** (Amazon Web Services EMEA SARL), which hosts the server and stores all data, as our
  processor.
- **Google**, for:
  - **Sign-in** (Google Sign-In on Android, Google Identity Services on the web);
  - **Place search and addresses** (Google Places and Geocoding APIs): when you type an event's location, the text you
    type and the app's language are sent by our server to Google; when you pick a place on a map, its coordinates are;
    to show where the band played, our server sends the locations of its events to Google and keeps the
    coordinates Google returns (without the events or who played them);
  - **the map** on Android (Google Maps SDK), shown when you pick a place or look at where the band played;
  - **the QR code scanner** on Android (Google Play services), used when you scan an invite;
  - **sync messages** on Android (Firebase Cloud Messaging): when a band changes, the server asks Google to deliver
    to its members' devices a message naming only the band's identifier, nothing else from the band; Firebase starts
    only after you sign in, and only for these messages (no analytics);
  - **profile photos**, loaded from Google's servers.

  Google processes this data under its own privacy policy: https://policies.google.com/privacy.
- **Buy Me a Coffee**, only if you open the "Support me!" link, which leaves the app.

## 5. Where the data is kept

The server and its data are in the AWS region **eu-central-1 (Frankfurt, Germany)**. The website and app files are
delivered through Amazon CloudFront, which serves them from locations close to you, including outside the European
Economic Area. Google may process data outside the EEA; such transfers are covered by the EU–US Data Privacy Framework
and standard contractual clauses.

All connections use HTTPS. Stored data is encrypted at rest by AWS.

## 6. How long we keep it

- **Band data, attachments and history**: as long as the band exists. Removed attachments and deleted bands are
  deleted, and their stored copies are kept for **30 days** for recovery from mistakes, then removed.
- **Backups**: a daily backup of the stored data, kept for **one year** in a locked store, used only to recover from
  data loss; data deleted in the app is gone from the backups when that time is up.
- **Your user record**: while you have an account.
- **Sessions**: a session lasts up to 30 days from its last use and at most 90 days, then you sign in again.
- **Invites**: until they expire or are withdrawn.
- **Failed invite attempts**: for the current hour.
- **Devices for sync messages**: until you sign out on that device or on all devices, leave your last band, the app
  is uninstalled (Firebase then reports its token invalid), or the device hasn't checked in for 60 days.
- **Server logs**: 14 days.
- When a leader moves you to the band's former members, you no longer see the band, but your member entry and what
  you recorded stay in it, because the band's accounts depend on them; your Google account stays linked to the entry
  so you can be welcomed back. A leader can also unlink it. When you leave a band yourself, your member entry and what
  you recorded stay in it as well, and your Google account is unlinked from it.

## 7. Data on your device

The app keeps a copy of your bands, changes not yet sent to the server, your session and your settings on your device,
so it works offline. On Android this is stored in the app's private storage and is excluded from Android backups; on
the web it is stored in your browser (IndexedDB and local storage). It is kept separately for each Google account, so
another account signed in on the same device does not see it. Signing out ends your session but keeps this copy, so
changes not yet sent are not lost; uninstalling the app, clearing its data, or clearing the site's data in your
browser removes it.

## 8. Your rights

Under the GDPR you have the right to access your data, to have it corrected or deleted, to restrict or object to its
processing, and to receive it in a portable format. To use them, write to **privacy@jacekgajek.com**.

**Deleting your data yourself, in the app:** no email needed. In Settings › Account, **Leave band** unlinks your Google
account from your member in that band, removes your photo, BLIK number and bank account number from it, and takes the
band off your account.
When you leave your last band, the server also deletes your photo link, your calendar link, your devices for sync
messages and your record of invite code attempts; your user record then keeps only your account identifier and the time of your last "Sign out on all
devices", so sessions you ended then cannot be used again. Invites you created as a leader stop working at once. You
can't leave a band you own: first make another leader its owner (Band members), or delete the band (Settings › Delete
band). Your member name, the money you recorded and the band's history of the changes you made (with your account
identifier) stay with the band, as described in 6.

If you can no longer sign in, write to **privacy@jacekgajek.com** from the email address of your Google account and we
will do it for you.

You also have the right to lodge a complaint with a supervisory authority; in Poland that is the President of the
Personal Data Protection Office (Prezes Urzędu Ochrony Danych Osobowych, https://uodo.gov.pl).

## 9. Children

The app is intended for adults (18 and over). It is not intended for anyone under 18, and we do not knowingly process their data.

## 10. Changes

If this policy changes, the new version is published at the same address with a new effective date. Significant
changes are announced in the app.
