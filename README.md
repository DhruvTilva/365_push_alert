# 365 Push Alert

A small, single-user, privacy-first Android app. It reads **text** notifications from the WhatsApp
groups you name, keeps them in a local inbox, lets you select and edit them, and publishes the
ones you choose to **one Discord channel** through a webhook — only when you press the button.

No backend, no account, no analytics, no Firebase. The only network traffic is the request to your
own Discord webhook (when you publish or press *Test connection*).

> **Honest scope:** Android notifications are not a complete record of a WhatsApp group. Some
> messages will be missed (muted groups, hidden previews, battery limits, grouped/summarised
> notifications). See [Known limitations](#known-limitations) and
> [docs/TECHNICAL_NOTE.md](docs/TECHNICAL_NOTE.md).

## Features

- Read-only `NotificationListenerService` for WhatsApp (`com.whatsapp`, `com.whatsapp.w4b`; the package list is editable).
- **Group filters**: add / edit / enable / disable / delete group names; per-group collected counters.
  Exact, case-insensitive, whitespace-normalised match. Nothing is collected until a group is enabled.
- Text only: photos, videos, voice notes, stickers, documents, GIFs, locations and summaries are ignored.
- Inbox with search, group filter, select one / many / all visible, edit, delete, status badges.
- Editing keeps the original notification text separate from the text that will be published; 2,000-character Discord limit enforced (never silently truncated).
- **Push Selected to Discord**: preview + confirmation, sequential sending in displayed order,
  progress, cancel between messages, rate-limit handling, bounded retries, per-message result.
- **Every Discord message is visibly separate, with a divider you fully control.** Discord merges consecutive posts from the same sender under one header, so each message starts with your own divider text and a blank line. Default: `### 📢 Terminal365 Update #12`. In Settings → *Divider above every Discord message* you can type any text, emojis and Discord formatting (`###` heading, `-#` small, `**bold**`), with the placeholders `{n}` (running number, +1 per published message), `{group}`, `{sender}`, `{date}`, `{time}`; a live preview shows the result, empty = no divider. The divider counts toward Discord's 2,000-character limit.
- **A received message is never split or altered**: one WhatsApp message = one inbox entry = one Discord message, even if it contains blank lines.
- Failed messages stay for retry; **Delivery uncertain** messages are never retried automatically.
- Sent History (can be turned off, then content is deleted as soon as it is published).
- Diagnostics: a small log of *why* notifications were accepted/rejected (reason codes only, never message text).
- Webhook stored encrypted with an Android Keystore key; never shown in full, never logged.
- Light/dark theme, backup disabled, only the `INTERNET` permission.

## Screens

Inbox · Sent · Settings (WhatsApp collection & group filters, Discord webhook, inbox/history, privacy, about/troubleshooting).

## Install the APK

The prebuilt debug APK is at **`artifacts/365-push-alert-debug.apk`** (application id `com.dhruvtilva.pushalert`, version 1.0.9, minSdk 26 / Android 8.0, targetSdk 34).

1. Copy the APK to your phone (download from the repo, USB, cloud drive, …) or use `adb install -r artifacts/365-push-alert-debug.apk`.
2. Open it on the phone and allow *Install unknown apps* for the app you opened it from.
3. Open **365 Push Alert**.

It is signed with the standard Android **debug** key in `keystore/debug.keystore` (password `android`, public by convention — it protects nothing). Updates signed with the same key install over the old version.

## First-time setup on the phone

1. **Allow restricted settings (Android 13+ only, sideloaded apps).** Settings → *Open app info* (button in the app) → ⋮ menu → **Allow restricted settings**. Without this Android greys out notification access.
2. **Grant notification access.** In the app: Settings → *Open notification access settings* → enable **365 Push Alert** → confirm. Back in the app the banner should say *Enabled and connected*.
3. **Add groups.** Settings → *Add group* → type the group name **exactly as it appears in WhatsApp** (case and extra spaces don't matter; partial names do not match). Keep each group switched on.
4. In WhatsApp make sure those groups are **not muted** and that Android shows message previews (Android Settings → Notifications → *Sensitive notifications / lock screen* must not hide WhatsApp content).
5. **Battery:** exclude 365 Push Alert from battery optimisation / "sleeping apps" so Android keeps the listener alive.

## Create and configure the Discord webhook

1. In Discord open the server → channel → **Edit Channel** → **Integrations** → **Webhooks** → **New Webhook** (needs *Manage Webhooks* permission).
2. Choose the channel, optionally rename it, then **Copy Webhook URL**. It looks like `https://discord.com/api/webhooks/<id>/<token>`.
3. In the app: Settings → *Discord* → paste the URL → **Save**. The field is hidden by default and the URL is stored encrypted; the screen only ever shows a masked version.
4. Press **Test connection**. It performs a read-only `GET` on the webhook and posts nothing to the channel.

A webhook can only post messages to its own channel (no reading, no other channels). Anyone with the URL can post there, so keep it private; if it leaks, delete the webhook in Discord and create a new one.
Messages are sent with `allowed_mentions: {parse: []}`, so forwarded text containing `@everyone` or `@role` does **not** ping anybody.

## Publish messages

1. Open **Inbox**, tick the messages you want (or *Select all* for what is currently visible). Use **Edit** to change wording first.
2. Press **Push Selected to Discord**. A dialog lists exactly what will be sent, in order. Confirm with **Send**.
3. Watch the progress card. Published messages move to **Sent**; failures stay in the inbox with the error; press **Cancel after current** to stop between messages.
4. For a message marked **Delivery uncertain** (timeout after sending), open Discord, then press **Resolve** and say whether it arrived. Sending it again without checking may create a duplicate.

## Troubleshooting missing notifications

- Status banner not green → toggle notification access off/on in Android settings; make sure *restricted settings* were allowed (step 1).
- Settings → **View diagnostics** shows the reason for each recent rejection (e.g. *group is not in the configured list*, *no readable text*).
- Group name must match the title WhatsApp shows in the notification. If WhatsApp shows `Group (3 messages)` that suffix is ignored automatically.
- Muted groups, *Hide sensitive content*, Do-Not-Disturb rules, or WhatsApp's notification settings can suppress the notification entirely — then nothing can be captured.
- Aggressive battery managers (Xiaomi, Huawei, Samsung "sleeping apps") may kill the listener; whitelist the app.

## Privacy & security

- Only notifications from the configured WhatsApp packages are read; other apps' notifications are ignored without being inspected.
- Only conversations whose title exactly matches an **enabled** group are stored. Rejected notifications leave only a reason code behind — not their text, not their title.
- Data lives in the app's private storage on the phone. `allowBackup=false` plus data-extraction rules exclude it from cloud backup and device transfer.
- The webhook is encrypted with AES-256-GCM using a non-exportable Android Keystore key; the plaintext is never written to preferences or logs. No secrets are in Git.
- Nothing is published automatically; every batch needs your confirmation.
- Notification access is a powerful permission; you can revoke it in Android settings any time, or switch collection off inside the app.

## If the app closes on start

A crash is recorded locally and shown on the next launch as an on-screen report with a **Copy report** button (nothing is sent anywhere). Send that text to the developer. **Clear and retry** dismisses it.

Version history: 1.0.1 fixes a start-up failure seen on a real phone by moving to a Kotlin 1.9.25 toolchain whose bytecode the `dx` dexer converts reliably (the 1.0.0 build used libraries containing `invokedynamic` call sites) and by dropping `kotlinx-coroutines-android` in favour of a small own main-thread dispatcher.

## Known limitations

- Notification-based capture is **best-effort**. Missing, delayed, truncated, summarised or hidden notifications cannot be recovered. Long messages may arrive cut off by WhatsApp (flagged *may be truncated*).
- Group identification relies on the notification title / conversation title. If WhatsApp doesn't expose one, the message is rejected (false negatives are preferred over leaking other chats).
- Messages from a *community*'s sub-groups are matched by the sub-group name shown in the notification only.
- Media captions are not collected: a leading media marker (📷 🎥 🎤 …) makes a notification "attachment" and it is ignored.
- Discord has no idempotency key for webhooks: after a timeout the outcome is unknowable, so the app marks it *uncertain* instead of guessing. Exactly-once delivery cannot be guaranteed.
- Sending runs in the app process without a foreground service; if Android kills the app mid-batch, in-flight messages become *Delivery uncertain* on next start.
- **This build was verified by automated JVM tests, packaging checks and API-level audit — it has not been run on a physical device or emulator** (none was available in the build environment). Please see "Verification status" below.

## Verification status

| Area | Status |
|---|---|
| 190 JVM tests (parser, group filter, de-dup, store/SQL, migrations, editing, state machine, Discord client, real HTTP transport against local servers, publisher scenarios, secret storage) plus Robolectric tests that launch the real Application/Activity on real Android framework code (real SQLite and Views) | executed, all pass |
| Kotlin compile with warnings-as-errors; permission / secret / logging static checks | executed, pass |
| APK builds, aligned, signed (v2/v3), manifest & permissions inspected with `aapt` | executed, pass |
| Android API use audited against min SDK 26 | done (no newer API used) |
| Installing and running on a device/emulator, real notification capture, UI rendering | **not done** |
| Android Lint, Compose, Room, WorkManager | not applicable / unavailable (see below) |

## Architecture

```
src/main/kotlin/com/dhruvtilva/pushalert/
  domain/    Models (statuses, entities, settings), TextRules (normalising, 2000-char validation)
  collect/   RawNotification → NotificationParser → GroupMatcher → CollectionEngine
             PushAlertListenerService (+ NotificationAdapter: StatusBarNotification → RawNotification)
  data/      Db (thin SQL interface) + Schema/migrations, Store (inbox, history, groups, dedupe, diagnostics),
             Settings (SharedPreferences settings, Keystore-encrypted SecretStore)
  discord/   WebhookUrl (validation, redaction), Http (transport), DiscordClient, BatchPublisher
  ui/        MainActivity + Inbox/History/Settings screens (programmatic Views), UiKit
  PushAlertApp.kt  hand-wired object graph
```

The parser, matcher, store, client and publisher have **no Android dependency in their logic** (SQL goes
through a tiny `Db` interface; tests run the real SQL on `sqlite-jdbc`), which is why they are unit-testable.

### Why not Compose / Room / DataStore / WorkManager?

The environment this was built in could not reach Google's Maven repository or Android SDK downloads
(blocked by the network policy), so the Android Gradle Plugin and every AndroidX/Compose library were
unobtainable. The app therefore uses framework APIs instead: **Views** instead of Compose,
**SQLiteOpenHelper + a small migration framework** instead of Room, **SharedPreferences** instead of
DataStore, `HttpURLConnection` instead of OkHttp, and no WorkManager (sending is a user-triggered coroutine
batch; nothing needs deferred background work). Functionality is unaffected; it also keeps the APK at ~1.4 MB.

## Build

Requirements: JDK 21, Gradle (wrapper included), internet access to Maven Central, and an Android
command-line toolchain providing `dx`, `aapt2`, `aapt`, `zipalign`, `apksigner` (build-tools) and a `platforms/android-*/android.jar`.
On Debian/Ubuntu: `sudo apt install aapt apksigner zipalign dalvik-exchange libandroid-23-java android-sdk-build-tools zip`
(that is what was used; `-PandroidSdk=/path` or `ANDROID_HOME` points at another SDK).

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug verifyApk
```

- `testDebugUnitTest` – JVM unit tests.
- `lintDebug` – project static checks (warnings-as-errors compile, no embedded webhook URLs, no sensitive logging, only `INTERNET` permission, backup off). This replaces Android Lint, which needs AGP.
- `assembleDebug` – Kotlin → `dx` → `aapt2` → `zipalign` → `apksigner`; writes `artifacts/365-push-alert-debug.apk`.
- `verifyApk` – prints id/version/permissions/signature.

Note: the build is not the standard AGP pipeline (see `build.gradle.kts` header). With full Google Maven access it could be migrated to AGP without touching the Kotlin sources.
