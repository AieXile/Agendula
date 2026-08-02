# Agendula — storage and sync

> Decided direction, captured 2026-08-01. Supersedes the earlier "Posture B =
> bundle OpenTasks" working notes, which are withdrawn (see
> [Dead ends](#dead-ends--do-not-revisit)). This is the detailed companion to
> `ARCHITECTURE.md` §7 and the `ProviderResolver` comments, **and it redefines
> what Posture B means** — those two need a follow-up edit.
> `ROADMAP.md` / `PLAN.md` remain known-stale and are due a deliberate pass;
> this document does not attempt it.

## The plan, in short

**What we're building**

1. **Our own provider.** Vendor the Apache-2.0 dmfs task provider in-tree as
   `:provider`, renamed to our own authority and permission namespace. Our
   database, our namespace — coexists with everything, replaces nothing.
2. **Our own sync.** An Agendula sync adapter, so remote storage never depends
   on another app's roadmap.
3. **The user picks the mode.** Local-only · Synced · External provider.
4. **Least permission.** Ask only for what the chosen mode needs, when it needs
   it.
5. **Kit-first.** Anything that isn't task-domain goes to floret-kit.

**In what order**

| # | Step | Why now |
|---|---|---|
| 1 | Merge `fix/provider-interaction-review` | unmerged and rotting; touches the same permission flow as step 2 |
| 2 | Vendor `:provider` under our own authority | the identity, done once — and it ships a complete local-first app |
| 3 | Export / backup | our data now lives only in our app's private storage |
| 4 | File the DAVx5 issue | cheap, non-blocking, serves F-Droid users |
| 5 | Sync adapter | the 1.x arc; design discussion pending |

Everything below is the reasoning behind those choices, the alternatives that
were rejected, and the constraints they have to survive.

---

## The decision

Agendula gets its **own identity all the way down** — its own task database
under its own authority, its own sync, and a storage mode the user picks. It
does not adopt, replace, or impersonate another project's provider.

Four parts:

1. **Own DB, bundled in-process.** Vendor the Apache-2.0 dmfs
   `opentasks-provider` as an in-tree `:provider` Gradle module, renamed to
   authority `de.jeanlucmakiola.agendula.tasks` with permissions
   `de.jeanlucmakiola.agendula.permission.READ_TASKS` / `…WRITE_TASKS`. Not a
   separate provider *app*; not a schema written from scratch. We keep the dmfs
   `TaskContract` shape — it's proven, it's what our whole data layer already
   speaks, and it's what every CalDAV engine already understands — we just own
   the namespace it lives in.
2. **Own sync adapter**, so remote storage never depends on another app's
   roadmap. Protocol coverage is deliberately open — separate discussion.
3. **The user chooses the backend**: local-only, synced, or an external provider
   that's already on the device.
4. **Ask for only what the chosen mode actually needs**, at the moment it needs
   it.

Plus a standing rule: **anything that isn't task-domain goes to floret-kit.**

### The vocabulary, redefined

`ARCHITECTURE.md` §7 and `ProviderResolver`'s KDoc still describe Posture B as
"bundle OpenTasks and find `org.dmfs.tasks` first." Replace with:

- **Posture A** — front-end over an *external* provider (OpenTasks, tasks.org).
  Still fully supported; it stops being the default and becomes a **user
  choice**.
- **Posture B** — our own bundled provider under **our own** authority.
  Coexists with everything; replaces nothing.

The A/B seam itself is unchanged and still earns its keep: `ProviderResolver` is
the only thing that knows an authority, `AndroidTasksDataSource` the only thing
that touches a resolver. UI, ViewModels, domain and repository are untouched by
all of this.

---

## Why not squat `org.dmfs.tasks`

The rejected plan was to bundle the provider under dmfs's own authority so
DAVx5 would sync into it unwittingly. Reasons it's out, in order of how badly
each one bites:

1. **It is a structural identity mismatch, and the resulting bug is invisible to
   both sides.** Content-provider *authorities* are how a sync engine finds a
   provider, but Android **account visibility is keyed by package name**: since
   API 26 an app only sees accounts whose authenticator has made them visible to
   *its package*, and `GET_ACCOUNTS` alone no longer suffices. A sync engine's
   allowlist would name the package `org.dmfs.tasks`, not
   `de.jeanlucmakiola.agendula`. So the bundled provider could find **zero**
   accounts — and the dmfs provider uses account enumeration to prune task lists
   whose account has gone away. The failure mode isn't "no sync", it's "our
   provider quietly purges synced lists." *(Reasoned from the platform rules,
   not from having read DAVx5's source — but the class of bug is structural, and
   every future place anything keys on package rather than authority is a fresh
   instance of it.)* An explicit integration under our own name makes this bug
   impossible by construction.
2. **Play Store install-time landmine.** Two apps cannot declare the same
   authority (`INSTALL_FAILED_CONFLICTING_PROVIDER`) or the same `<permission>`
   name (`INSTALL_FAILED_DUPLICATE_PERMISSION`, waived only for identical
   signing certs). Anyone with OpenTasks installed gets a failed install,
   surfacing as one-star reviews we can't usefully answer.
3. **Migration data loss.** "Uninstall OpenTasks first" takes its DB with it.
   CalDAV-synced tasks reconcile back; local-only tasks are simply gone, and
   OpenTasks has no export (dmfs/opentasks #170, #204, #71 — years-old,
   unimplemented; their wiki punts to a desktop client).
4. **Squatting another project's namespace doesn't scale.** At low install
   counts nobody notices. At scale we'd be generating issues on dmfs's tracker
   that aren't dmfs's fault, and silently maintaining a schema fork under their
   name.

---

## The `:provider` module

**Source.** dmfs `opentasks-provider`, Apache-2.0. Target the **1.4.2** source
(DB version 23 — the version that actually carries `is_recurring`; tasks.org's
fork is DB 22 and lacks it, which is why `TaskMapper.task` reads `rrule`/`rdate`
for recurrence detection rather than trusting the column).

**Layout: in-tree module, not a git submodule.** floret-kit is a submodule
because we co-develop it. This is a fork we will sync from upstream
approximately never, so in-tree is simpler for both stores and honest about what
it is. Ship a `provider/PROVENANCE.md`: upstream commit, and every change we
made.

**License hygiene, on day one.** The app is MIT, the provider is Apache-2.0 —
permissive into permissive, fine — but the module keeps its Apache-2.0 headers,
`LICENSE`, and `NOTICE`. Ten minutes now; embarrassing to retrofit once it's in
two store listings.

**What we change:**

- Authority → `de.jeanlucmakiola.agendula.tasks` (it's already a string
  resource, `opentasks_authority`).
- Permission names → `de.jeanlucmakiola.agendula.permission.*`. These are
  **hardcoded in the AAR manifest**, which is the single clearest reason
  vendoring is mandatory rather than merely preferable — you cannot rename them
  in a prebuilt artifact without `tools:` node surgery we'd rather not ship.
- Drop `<uses-permission android:name="android.permission.GET_ACCOUNTS" />`.
  We own our own accounts, so we don't need it — but note the provider's
  account-cleanup path is written *assuming* it, so this is a review-and-rework
  item, not a free deletion. **Verify** the provider's local-list/local-account
  path works with no account present at all; that's the entire local-only mode.
- Drop the exported `BOOT_COMPLETED` / `TIME_SET` / `TIMEZONE_CHANGED` receiver,
  or keep it deliberately and give it an explicit `android:exported`. The AAR is
  from the `targetSdk 29` era; AGP hard-errors on a merged manifest with an
  intent-filtered component and no explicit `exported` once targetSdk ≥ 31, and
  we're on 36. Fixed at source instead of patched around.
- Modernize the build: it ships `minSdk 21` / `targetSdk 29`, Robolectric 3.5.1,
  JUnit 4.12. We're `minSdk 29` / `targetSdk 36` and Play raises its target-API
  floor annually, so this isn't optional upkeep.

**Our data now lives in our app's private storage.** Uninstall means deletion.
That single fact is what promotes export/backup from "nice to have" to a v1
feature — see [Storage modes](#storage-modes--the-users-choice).

---

## Storage modes — the user's choice

| Mode | Backing store | Sync | Needs |
|---|---|---|---|
| **Local** | our bundled provider | none | no permissions at all — same-uid provider access needs no grant |
| **Synced** | our bundled provider | our sync adapter | network + an account the user configures |
| **External** | OpenTasks / tasks.org | whatever that provider's engine does (DAVx5 …) | that provider's `READ`/`WRITE_TASKS`, granted at runtime |

Local and Synced are the same store — Synced is Local with an account attached,
so switching on sync is not a migration.

**Resolver ordering needs deciding.** Today `ProviderResolver.CANDIDATES` is a
fixed priority list and the first hit wins. Once we bundle our own provider,
"first hit" is the wrong rule: someone who used Agendula locally and *later*
installs DAVx5 + OpenTasks would see an external candidate outrank the provider
that actually holds their data. Options: rank ours first whenever it's
non-empty, or make the mode an explicit Settings choice (it's user-visible
either way, so probably both — auto-pick a sane default, let Settings override).

**Export/backup is a v1 feature.** Not, as previously framed, a migration safety
net for "uninstall OpenTasks" — that scenario no longer exists. It's data
portability for Local-mode users, whose tasks otherwise exist in exactly one
place with no second copy. On Play, where most users won't have a sync engine,
that's the majority.

---

## Sync — own adapter

**Decided:** Agendula ships its own sync. Not because DAVx5 is bad, but because
depending on it makes one external maintainer's roadmap the gate on our core
feature — the same shape of dependency the whole identity decision exists to
escape.

The two precedents diverge and the choice between them is the whole point:
tasks.org has its own authority **and its own sync** (sovereign); jtx Board has
its own authority and **depends on DAVx5** (and got added, though a working
relationship with bitfire is part of that story). We're taking the tasks.org
shape.

**Play sharpens this.** DAVx5 is a paid app on Google Play and free only on
F-Droid — *worth confirming, since it's load-bearing* — which means most Play
users will never have it. Lobbying bitfire is therefore an **F-Droid-audience
feature, not a sync strategy**.

**Still file the DAVx5 issue** — cheap, non-blocking, real value for F-Droid
users. And make it the strongest possible version of the ask: our provider *is*
the dmfs provider with renamed strings, so it's byte-identical contract
compliance and a small enum-shaped addition with near-zero ongoing maintenance
for them. Say that explicitly. "Here's a change that can't break anything" lands
very differently from "please support my app."

**Open — the next discussion.** Protocol coverage ("support as much as
possible"), the account model, conflict resolution, and where the DAV/iCalendar
work lives. One constraint to settle early: we're MIT; `dav4jvm` is Apache-2.0
and fine, but **verify `ical4android`'s license** before assuming it's usable.

---

## Permissions — only what the mode needs

The manifest is static, so "request only what we need" is really two different
mechanisms, and conflating them is how apps end up over-permissioned:

- **Runtime (dangerous) permissions** — genuinely stageable. Ask at the moment
  the feature is used, never up front.
- **Install-time (normal) permissions** — declared unconditionally; the only
  lever is **not declaring them until the feature ships**, and not letting a
  bundled dependency drag in ones we don't use.

| Permission | When |
|---|---|
| *(none)* for our own provider | same-uid access needs no grant — `ProviderStatus.NEEDS_PERMISSION` must never fire in Local/Synced mode |
| `org.dmfs.permission.*`, `org.tasks.permission.*` | requested **only** when the user selects External mode; declared always (static manifest) |
| `POST_NOTIFICATIONS` | when reminders are first enabled |
| `USE_EXACT_ALARM` / `SCHEDULE_EXACT_ALARM` | when exact due-time reminders are used. Note Play reviews `USE_EXACT_ALARM` and requires the app to be a calendar/alarm/task app — we qualify, but it needs a justification in the listing |
| `INTERNET` | **don't declare it until sync ships** |
| `GET_ACCOUNTS` | never — stripped from the vendored provider; our own account type doesn't need it to see its own accounts |

**Work item:** the permission gate in `RootScreen` / `PermissionViewModel` /
`ProviderResolver.hasPermission` currently assumes an external provider always
needs a grant. It needs a bypass for our own provider. Modest, but it's the
exact flow `fix/provider-interaction-review` just touched — merge that first.

---

## What lands in floret-kit

Standing rule, matching the kit's own thesis (*share the mechanics, keep the
look — the kit never knows about a specific app's domain*): if it isn't
task-domain, it goes to the kit.

| Candidate | Kit module | Note |
|---|---|---|
| ContentProvider seam — `ColumnReader`, failures, observer→Flow | `core-provider` | **already on the kit's deferred list**, blocked on migrating Calendula to the name-based reader. Bundling our own provider is the forcing function that makes this worth doing. |
| Runtime-permission staging — request/state machine, rationale plumbing, "ask at point of use" | new, e.g. `core-permissions` | pure mechanics, and Calendula has the identical problem |
| DAV client + iCalendar parse/serialize | new, e.g. `core-dav` | **the big one.** Calendula is a calendar app; it needs the same primitives. Worth designing for two consumers from the start rather than extracting later |
| Export/backup plumbing — SAF, file writing, share-out | kit | the *serialization* of tasks is domain; the plumbing isn't |
| Sync-adapter/account scaffolding | kit, probably | the `AbstractThreadedSyncAdapter` + authenticator boilerplate is identical everywhere; the delta logic is domain |

**Stays app-local:** the vendored `:provider` module (task-specific, and
Apache-2.0 against the kit's MIT), `TaskContract` and the mappers, domain models
and smart lists, all screens, and the reminder *scheduler* (per the kit's
existing "not shared" call — Agendula pulls, Calendula pushes).

---

## Distribution — F-Droid and Play from day one

Not on either yet; both are targets, so build *for* them rather than retrofitting.

- **F-Droid** requires from-source. The in-tree `:provider` module satisfies it;
  a JitPack artifact would not. floret-kit's composite build already keeps the
  kit from-source, and the reproducibility guard (`vcsInfo { include = false }`
  in `app/build.gradle.kts`) is already in place.
- **Play** requires a rising target-API floor, a data-safety declaration, and
  justification for `USE_EXACT_ALARM`. It also means dangerous permissions we
  don't use are a liability, not just clutter — which is most of why
  `GET_ACCOUNTS` and the stray receiver come out of the vendored provider.
- **Parked, not solved:** dual-distribution signing. F-Droid reproducible builds
  verify against *our* signed APK; Play App Signing re-signs with Google's key.
  Both can coexist, but it needs a deliberate pass before the first Play upload.
  See `RELEASING.md`.

---

## Dead ends — do not revisit

- **One APK that detects at install time and adapts.** Impossible.
  `<provider>` authorities and `<permission>` declarations are frozen at build
  time and read by the OS at install; there is no install-time hook where our
  code runs. And unlike a component, a `<permission>` cannot be runtime-toggled
  — no `setComponentEnabledSetting` equivalent.
- **Dynamic feature modules** to deliver the provider conditionally. Conditions
  are limited to hardware features / SDK / country — there is no "only if app X
  is absent" — and they require Play, so they're dead for F-Droid regardless.
- **`frontend` / `standalone` build flavors.** Two flavors means two
  `applicationId`s (two listings, two signing lines, and switching costs a user
  their local data), or one `applicationId` and they can't coexist in a repo
  anyway. Obsolete now that our provider coexists with everything instead of
  replacing anything.
- **Maven Central for the provider.** `org.dmfs:opentasks-provider` *is* there —
  but only up to `1.1.8.1` (2016, `jar` packaging, 3 versions). No DB 23.
  Verified.
- **JitPack (`com.github.dmfs.opentasks:opentasks-provider:1.4.2`).** Has the
  right version, but it's a prebuilt artifact (fails F-Droid from-source), it
  can't have its hardcoded permission names renamed, and adding JitPack widens
  the dependency trust surface — `settings.gradle.kts` is currently `google()` +
  `mavenCentral()` only, under `FAIL_ON_PROJECT_REPOS`. *Still usable for a
  throwaway spike* (a library string resource can be overridden from the app
  module, so the authority rename works), but not for anything we ship.

---

## Sequencing

1. **Merge `fix/provider-interaction-review`** (`47cf99a`, currently unmerged
   into `main`). It's blocking nothing and rotting, and it touches the exact
   permission flow step 2 changes.
2. **Vendor `:provider`** under our own authority and permission namespace, with
   the permission-gate bypass. This is the identity, done once, done right —
   and it ships a complete local-first app to both stores.
3. **Export/backup.** Now a v1 feature, not a migration hack.
4. **File the DAVx5 issue.** Non-blocking, cheap, serves F-Droid users.
5. **Sync adapter.** The 1.x arc; design discussion pending.

**Scope honesty:** the withdrawn notes costed this at "2–4 days shippable, +1
week for F-Droid." Steps 2–5 are a substantially larger program than that, and
the roadmap should say so rather than inheriting the old estimate.

---

## Open questions

1. **Sync protocol coverage**, account model, conflict resolution — the next
   discussion.
2. **Resolver ordering / mode selection UX** once our provider coexists with
   external ones (see [Storage modes](#storage-modes--the-users-choice)).
3. **Does the vendored provider work with no account at all?** Local-only mode
   depends on it entirely. First thing the vendoring work should prove.
4. **`ical4android` licensing** vs our MIT.
5. **jtx Board** as an additional External-mode candidate — richer contract,
   later. (`PLAN.md` decision #3, still open.)

---

## Related

The provider-interaction review on `fix/provider-interaction-review` fixed,
among others: recurrence-aware editing (routes through the instances URI),
all-day UTC handling, the `DUE`/`DURATION` collision, per-task reminders (Alarm
property rows), and flow-recovery robustness. That makes `ROADMAP.md` open
decision #5 ("recurrence-aware editing out of scope for v1") **stale**.
