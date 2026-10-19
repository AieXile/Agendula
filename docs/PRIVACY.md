---
title: Privacy Policy — Agendula
description: What Agendula does with your data. No servers, no account, no analytics — your tasks stay on your device unless you add a CalDAV server yourself.
updated: 2026-10-19
---

<!--
  THIS FILE IS THE POLICY. Edit it here, in a PR, reviewed like any other
  change — it is the source of truth.

  It is published at https://jeanlucmakiola.de/agendula/privacy, which is what
  the app's Settings → About → Privacy policy row opens and what the Play
  Console field must hold. That page holds no copy of the prose: the website
  build checks this repository out beside itself and renders this file through
  an Astro content collection, so there is exactly one copy of the policy
  anywhere and the two cannot drift. An edit here reaches the live page on the
  site's next build.

  The page supplies its own <h1> from the `title` above, so this body starts at
  the first section — do not add one. This comment is HTML so that it cannot
  render on the published page.
-->

**Last updated:** 19 October 2026  
Applies to the Android app **Agendula** (package `de.jeanlucmakiola.agendula`),
all versions and all distribution channels.

## In short

Agendula has no servers, no user accounts and no analytics. Your tasks live on
your device. They leave it in exactly one case: if you set up a CalDAV account
yourself, they are synchronised with **the server you entered** — and with
nothing and no one else. Nothing is ever sent to the developer.

## 1. Controller

IT-Dienstleister | Jean-Luc Makiola  
Mahlerstraße 10  
14772 Brandenburg an der Havel  
Email: [support@jeanlucmakiola.de](mailto:support@jeanlucmakiola.de)

## 2. No data collection by the developer

Agendula contains **no analytics, no tracking, no advertising, no
crash-reporting SDK and no third-party service that reports anything
anywhere**. No user profile is created, no advertising or device identifier is
generated, and no data is shared with or sold to anyone. There is no Agendula
account, and the developer operates no server that the app talks to.

All of this is verifiable in the
[source code](https://codeberg.org/jlmakiola/agendula), which is public.

## 3. Where your tasks live — your choice

- **On your device (the default)** — your task lists, tasks and reminders are
  kept in Agendula's own database inside the app's private storage. Nothing is
  published to other apps, and uninstalling the app removes it.
- **In a tasks provider you already use** — OpenTasks or tasks.org. Agendula
  then reads and writes that app's task database through Android's provider
  mechanism, after you grant its read/write permission. Whatever already
  synchronises that provider (DAVx5, SmoothSync, DecSync CC, …) keeps doing so,
  unchanged; that synchronisation is performed by those apps, not by Agendula,
  and their privacy policies apply to it.

## 4. CalDAV sync — the only case where your tasks leave the device

Sync is optional and off until you add an account. If you add one, everything
below happens between your device and **the server you nominated**, and nowhere
else.

### What is stored on your device

The server address, your username, and your password or app password. The
password is encrypted with a key held in the Android Keystore, which cannot be
exported from the device.

### What is transmitted, and to whom

- The tasks in the synchronised lists, as standard iCalendar (`VTODO`) data —
  including their reminders, as `VALARM`s — and the credentials needed to
  authenticate.
- Requests carry the user agent `Agendula (Android)` — a fixed string, so that
  you can recognise and revoke the session on your server. No device identifier
  is sent.
- Connections are HTTPS. Cleartext HTTP is refused, so credentials are never
  sent over an unencrypted connection.

Nothing is sent anywhere else. In particular, nothing is sent to the developer.

Under Google Play's Data Safety definitions this counts as **collected** — Play
defines collection as transmitting data off the device, regardless of who
receives it — and **not shared**, because the only recipient is the server you
nominated. Data is encrypted in transit.

### Finding your server

When you type a server address or an email domain, Agendula follows the
standard discovery procedure (RFC 6764): a DNS lookup for the `_caldavs._tcp`
service record of that domain, then `/.well-known/caldav` on the host. The DNS
query goes to whichever resolver your device or network uses, and the requests
go to the domain you typed — no directory of servers is consulted and no lookup
is sent to the developer.

### Signing in to a Nextcloud

If the server is a Nextcloud, Agendula uses Nextcloud's Login Flow v2: your
browser opens *your own server's* login page, you authorise there, and the
server hands the app a dedicated app password. Agendula never sees your actual
account password. The app password appears in your server's "Devices &
sessions" list as `Agendula (Android)`, and you can revoke it there at any
time. Removing the account in Agendula revokes it too, where the server
supports that.

### Your server's own policy

Your CalDAV provider has its own privacy policy, and your data on their server
is governed by it. Agendula has no relationship with them.

A note on certificates: Agendula trusts private certificate authorities that
you have installed in your device's user store, because self-hosted servers
routinely use them. That is a deliberate trade-off in favour of self-hosters —
any CA installed on your device (for example by an employer's management
profile) can, in principle, intercept traffic from the app, as it can from
other apps that make the same choice.

## 5. Other data Agendula handles on your device

### Reminders and notifications

Due-date reminders are scheduled by the app itself and displayed as local
notifications. Nothing is sent to a push service — there is no push service.

### Export files

You can export your tasks as standard iCalendar `.ics` files. Agendula writes
exactly the file you select through Android's system file picker, and has no
access to other files.

### App settings

Your preferences (theme, language, list and reminder defaults and similar) are
stored locally on your device and are removed when you uninstall the app.

## 6. Backups

If Android Auto Backup is enabled on your device, your tasks and settings may
be backed up to your own Google account, under Google's terms — the developer
has no access to it. What travels is Agendula's own task database and your
settings, and nothing else: the backup rules name those explicitly, which makes
everything not named — including the archived copy the app keeps of an older
version's database — excluded by default. Two things are deliberately kept out
of that backup: your stored CalDAV password, and Agendula's per-device sync
bookkeeping. After restoring onto a new device you therefore sign in to your
server again.

## 7. Crash reports

If Agendula crashes, it offers to report the problem. Nothing is sent
automatically, even though the app has network access. The report is copied to
your clipboard and your browser is opened with the project's issue tracker, the
text pre-filled. **You see the full content, you decide whether to submit it,
and you can edit or discard it.**

Such a report contains:

- app version,
- Android version,
- device manufacturer and model,
- your device language,
- the timestamp,
- and the technical stack trace.

It is built from that fixed list and nothing else: **no** task data, **no**
server address or credentials, **no** account names, **no** log files and
**no** personal identifiers.

If you choose to submit it, the report becomes a public issue on the project's
issue tracker at Codeberg, operated by Codeberg e. V. Their privacy policy then
applies to that submission.

## 8. External links

The app links to the source code, the licence, the issue tracker, the
translation platform (Weblate) and a voluntary donation page (Ko-fi). Following
one of these links opens your browser and leaves the app; the privacy policy of
the respective website then applies. Agendula transmits no data of yours in the
process — it only opens the address.

## 9. Permissions and why they exist

This is the complete list the released app declares — you can check it against
the app's entry in F-Droid, or against `app/src/main/AndroidManifest.xml` in the
source:

- `INTERNET`, `ACCESS_NETWORK_STATE` — CalDAV sync with the server you
  configure, and checking whether a connection exists before trying. Without a
  CalDAV account, no connection is made.
- `READ_SYNC_SETTINGS`, `WRITE_SYNC_SETTINGS` — register the sync account with
  Android's sync framework so it can be scheduled.
- `POST_NOTIFICATIONS` — show reminders.
- `USE_EXACT_ALARM`, `SCHEDULE_EXACT_ALARM` — deliver reminders at the exact
  due time.
- `RECEIVE_BOOT_COMPLETED` — re-register pending reminders after a restart.
- `org.dmfs.permission.READ_TASKS` / `WRITE_TASKS` and
  `org.tasks.permission.READ_TASKS` / `WRITE_TASKS` — optional, requested only
  if you choose the external-provider storage mode, and only for the provider
  you selected (OpenTasks or tasks.org). All four are declared in the manifest
  because a manifest is static, but none is requested until you pick that mode.
- `WAKE_LOCK`, `FOREGROUND_SERVICE` — required by the Android system component
  used for scheduled background work (WorkManager); on older Android versions
  it needs them to run an expedited sync.

Agendula publishes no content provider of its own and declares no permissions
that other apps could request.

## 10. Distribution channels

Agendula is distributed through the project's releases on Codeberg, a
self-hosted F-Droid repository, Obtainium, and — where applicable — F-Droid and
the Google Play Store. When you download or update the app, the operator of
that channel processes data (such as your IP address) under their own privacy
policy. This is outside the developer's control and unrelated to the app's own
behaviour.

## 11. Children

Agendula is not directed at children and collects nothing about anyone.

## 12. Deleting your data

- **Remove a CalDAV account** from Settings → Accounts. This deletes the stored
  credential and, where the server supports it, revokes the app password. Task
  lists become device-only lists rather than being destroyed.
- **Remove an account and delete its local data** removes the lists and tasks
  as well.
- **Uninstalling the app** removes everything Agendula stored on the device.

Deleting data from your CalDAV server is done on that server; data in an
external tasks provider is deleted in that app.

## 13. Your rights

The developer stores no personal data of yours — the only data transfer the app
performs is between your device and a server you operate or chose. There is
therefore no data held by the developer to which rights of access,
rectification, erasure, restriction, data portability or objection (Art. 15–21
GDPR) could apply. Your tasks are exportable as standard `.ics` files from
within the app at any time. You may contact the address above with any
question, and you have the right to lodge a complaint with a supervisory
authority.

## 14. Changes to this policy

Should the app's functionality change in a way that affects data processing,
this policy will be updated and the date at the top adjusted. The history of
this file is public in the repository.
