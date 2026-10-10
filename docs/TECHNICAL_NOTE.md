# Technical note

## 1. What notification access can capture

A `NotificationListenerService` receives a `StatusBarNotification` for every notification the system
posts, including its `Notification.extras`: `EXTRA_TITLE`, `EXTRA_TEXT`, `EXTRA_BIG_TEXT`, `EXTRA_SUMMARY_TEXT`,
`EXTRA_TEXT_LINES`, and for MessagingStyle notifications `EXTRA_MESSAGES` (text, timestamp, sender),
`android.conversationTitle` and `android.isGroupConversation`. The service is read-only: it cannot
send, click, or reach WhatsApp's data. The app reads those fields only when the posting package is one of
the configured WhatsApp packages (default `com.whatsapp`, `com.whatsapp.w4b`).

## 2. Why completeness cannot be guaranteed

- A notification exists only if WhatsApp posts it: muted chats, disabled notification categories, Do-Not-Disturb, or the chat being open on screen produce none.
- Users or OEMs may hide content ("sensitive notifications", lock-screen settings) or redact text.
- WhatsApp may bundle or summarise ("5 new messages"), truncate long texts, or show only the latest messages of a MessagingStyle conversation. Messages dismissed before the service runs are gone.
- Battery managers can stop the listener; Android only guarantees delivery while it is bound.
- Group name is not a dedicated field; it is inferred from conversation title / title.

The app therefore never claims to be a complete archive, and it rejects (rather than guesses) whenever
the group cannot be identified.

## 3. Group-name matching

`NotificationParser` builds *title candidates* from the conversation title and the notification title:
the raw value, the value with a trailing `(N messages)` removed, and for the legacy `Sender @ Group`
form the part after ` @ `. Generic titles (`WhatsApp`, `WhatsApp Business`, `N new messages`, …) are discarded.

`GroupMatcher` compares each candidate with the configured groups after normalisation
(Unicode NFKC, invisible direction marks removed, trimmed, whitespace collapsed, lower-cased) and requires **equality** — no
substring or fuzzy logic. Outcomes: matched (exactly one enabled group), disabled, unknown, or **ambiguous**
(candidates match two different enabled groups → rejected). Group names that are generic are refused when you
add them. Individual chats (explicit `isGroupConversation=false`, or a `@s.whatsapp.net` shortcut id) are rejected
unless "also collect individual chats" is switched on. Kind is *unknown* when signals are missing or conflict; an
unknown kind is still subject to the exact title match, so only a title you typed yourself can match.

## 4. Grouped notifications

- `FLAG_GROUP_SUMMARY`, ongoing/system notifications, and texts like "5 new messages (from 2 chats)" are rejected as summaries — never turned into messages.
- MessagingStyle bundles yield one candidate message per `EXTRA_MESSAGES` entry (own text, timestamp, sender). Attachment placeholders inside the bundle are skipped individually.
- Notifications without structured messages yield at most one message (`EXTRA_BIG_TEXT` only if it is a longer version of `EXTRA_TEXT`). `EXTRA_TEXT_LINES` is *not* split because it carries no per-line timestamps, so repeats could not be told apart reliably.
- In the single-text form, `Sender: text` is split into sender/text unless the chat is known to be individual.
- Attachment policy (deterministic): a body that *begins with a media marker emoji* (📷 📸 📹 🎥 🎬 🎤 🎙 🎵 🎶 🎧 📄 📃 📎 🖼 📍 👤 📊) or that *as a whole* equals a placeholder such as `Photo`, `Video (0:12)`, `Voice message`, `Sticker`, `GIF`, `Document`, `<Media omitted>`, `2 photos` is rejected (ambiguous captions are rejected too). Ordinary sentences that merely contain "image"/"video" are kept. Texts ending with `…`/`...` are accepted but flagged *may be truncated*.

## 5. De-duplication

Fingerprint = SHA-256(source package, normalised group, normalised sender, whitespace-collapsed text, message timestamp-if-known).
A separate `seen_fingerprints` table stores **only hashes** with the time first seen.

- *Timestamped* fingerprints (MessagingStyle) are duplicates for as long as they are remembered (3 days). This is needed because Android re-posts earlier messages in each update of the same conversation; the same text sent again later has a different timestamp and is kept.
- *Untimestamped* fingerprints are duplicates only within 2 minutes (re-posts of the same notification); an identical message later is kept.
- The memory outlives deletion/sending, so a deleted or already-published message is not resurrected when a later notification repeats it.

Limitation: two different people sending the same text at the very same millisecond is indistinguishable only if sender is also identical; sender is part of the fingerprint, so this is practically impossible.

## 6. Delivery failures and ambiguous outcomes

Status machine: `PENDING/EDITED → SENDING → SENT | FAILED | DELIVERY_UNCERTAIN` (`SENT` is terminal; the row leaves the inbox).
`SENDING` is claimed atomically (`beginSending`), so a message can never be in two batches.
After a crash `SENDING` rows become `DELIVERY_UNCERTAIN` on next start.

Requests are `POST …?wait=true` with `{"content": …, "allowed_mentions": {"parse": []}}`, one message per request, sequentially, 400 ms apart plus any `X-RateLimit-Reset-After` when the bucket is exhausted.

| Result | Meaning | Action |
|---|---|---|
| 200 / 204 | Discord created the message | `SENT`, history entry (if enabled) with Discord message id |
| 429 | Not processed | wait `retry_after` (+250 ms), retry same message; max 5 attempts, never wait > 60 s → `FAILED`, batch stops |
| 5xx | Server problem | retry with 1 s, 2 s backoff, max 3 attempts → `FAILED`, batch stops |
| 400 and other 4xx | Content refused | `FAILED`, continue with next message |
| 401 / 403 / 404 | Webhook invalid/deleted | `FAILED`, batch stops (all would fail) |
| Failure *before* the request body is written (offline, DNS, TLS, connect timeout) | provably not sent | `FAILED`, batch stops, safe to retry |
| Failure *after* sending (read timeout, dropped connection, unexpected exception) | may or may not exist | `DELIVERY_UNCERTAIN`, **no automatic retry**, batch stops |

The user resolves an uncertain message by checking Discord: *delivered* → moved to history (marked "confirmed manually"); *not delivered* → `FAILED` (retryable); or simply select it and push again (dialog warns of a possible duplicate).
Discord webhooks offer no idempotency key, so exactly-once cannot be guaranteed. Redirects are never followed (so the token cannot be forwarded to another host). Cancellation takes effect between messages (and during waits); messages already published stay published.

## 7. Where credentials live

Only the Discord webhook URL is secret. It is validated (`https`, `discord.com`/`ptb`/`canary`/`discordapp.com`, numeric id, token charset/length, no query/user-info/port), encrypted with AES-256-GCM using a non-exportable key generated in the **Android Keystore**, and stored as Base64 ciphertext in a private preferences file (`secrets`) separate from ordinary settings (`settings`). The `WebhookUrl` class redacts itself in `toString()`; error messages and logs never include it. If the Keystore key is lost (e.g. new device, reset), the app reports the webhook as unreadable and asks you to re-enter it — it never falls back to plaintext. Backup and device transfer are disabled. Nothing secret is in source control (the checked-in debug keystore is the public, conventional Android debug key and only signs the debug APK).

## 8. Retention of published messages

On success the row is deleted from the inbox in the same SQLite transaction that inserts the Sent History row (`group, sender, published text, received/sent time, Discord message id, delivery status`). The *published* text is stored, not the original, when they differ.
With **Keep Sent History** off, no history row is written: the content disappears on success. History can be cleared entirely or per entry; "Delete all locally stored message content" removes pending messages, history and diagnostics. Pending and failed messages are never deleted automatically (not on restart, not on failure). Rejected notifications are never stored, only reason codes in the 100-entry diagnostics log.

## 9. Database

SQLite (`pushalert.db`, WAL). Tables: `group_filters`, `messages`, `sent_history`, `seen_fingerprints`, `diagnostics`. Version 2; `Schema` holds the v1 DDL and the ordered migration list (`1→2` adds `collected_count` with back-fill and the `diagnostics` table). Tests create a v1 database with data, migrate it, and assert the result equals a fresh v2 schema.

## 10. Build toolchain note (dexing)

The APK is dexed with `dx` (build-tools 29), because D8/R8 are only published on the unreachable Google Maven repository. `dx` is old, so the app deliberately uses the Kotlin **1.9.25** compiler with matching stdlib 1.9.25 and coroutines-core 1.8.1 (compiler and runtime library versions must match: a newer compiler emits helper calls such as `kotlin.coroutines.jvm.internal.SpillingKt` that older stdlibs lack), compiles lambdas/SAMs to classes (`-Xlambdas=class -Xsam-conversions=class`), and avoids `kotlinx-coroutines-android`. The remaining `invoke-custom` sites in the dex are in library classes the app never loads (comparator helpers, Future/Stream interop, thread-pool, debug agent).
