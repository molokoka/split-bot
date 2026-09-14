# Telegram onboarding & command UX — requirements

**Status: draft requirements, gathered from a live onboarding walkthrough.
No approach has been chosen yet — this is the input to a design pass, not
a finished spec.**

## Context

Triggered by testing the real onboarding flow in a group: adding the bot
via a DM "Add to Group" deep link, then typing `/start` manually. Several
rough edges surfaced in the messages the bot sends during that flow and in
how the command list is presented. This document collects them in one
place before any of them are designed or built.

Related, already shipped this session (not part of this doc): `/balance`,
`/balances`, and `/settle_suggest` used to say "You're all settled up!"
even when fewer than two members had registered, which read as "nothing
to do" when the real blocker was that nobody else could be `@mentioned`
yet. Fixed in `MessageFormatting.kt` — see git history, not repeated here.

Everything below has shipped except item 6 — current behavior is the code
in `telegram/src/main/kotlin/split/telegram/commands/HelpCommand.kt`, not
this doc; see git history for how each item got there.

## Requirements

### 1. Clarify the repeat-`/start` confirmation — shipped

### 2. Stop repeating the full onboarding text on every `/start` — shipped

Items 1 and 2 turned out to be the same fix. `StartCommand.handle` (typed
`/start`) no longer calls the full `groupWelcomeText` at all — it sends a
single short line: "✅ You're registered — I'll recognize you when you're
`@mentioned` in /split." The full welcome (currency, mention rule,
Commands button) is now delivered exactly once, by `welcomeNewGroup` when
the bot joins — nothing else repeats it. `TYPED_START_RULE` and the
`startRule`-parameterized `groupWelcomeText` are gone; there's only one
welcome text now.

Once there's more than just the caller registered, `/start` also appends
who else is already in: "Here's who's registered already: @alice, @bob —
/members to check out members." This was tried first on the empty-balance
messages (`/balance`, `/balances`, `/settle_suggest`) instead, but that
was reverted — decided the roster belongs in exactly one place (`/start`,
where someone would actually want it right after registering), not
repeated across every command that happens to have nothing to show.
`/members` itself now shows both the @username and the display name
together ("@alice_w (Alice)") once a username is known, instead of
picking one — useful specifically because this roster now leans on it.

### 3. Add a `/commands` command — shipped

`/commands` is a distinct command from `/help` (which stays as the
full-dump shortcut, unchanged). See `CommandsCommand` in
`HelpCommand.kt`.

### 4. Split the command list into basic + advanced, on demand — shipped

`/commands` sends a trimmed Essential list with four buttons: 🧾 Split,
💰 Balance, 🤝 Settle (one row), and ⚙️ Advanced (its own row below) —
tapping Advanced sends the Advanced section as its own message. The
welcome message's button now opens this same flow instead of dumping
everything at once.

### 5. Make `/split` a button, and defer its documentation — shipped

The 🧾 Split button reuses `SplitExpenseCommand.handle` with empty args,
which already launches the existing guided, one-field-at-a-time draft
flow — so no new "syntax help" text was needed. 💰 Balance and 🤝 Settle
got the same treatment (delegate to the existing command with no args);
`/settle` has no guided draft, so tapping it bare just shows its existing
usage hint, which is fine for now.

### 7. Stop the deep-link `/start` from adding its own duplicate message — shipped

The deep-link `/start` (`ADD_TO_GROUP_DEEP_LINK_PAYLOAD` — Telegram's
"Add to Group" companion event, sent to the group the moment someone
picks it) went through two designs before landing here: first a
fresh/stale heuristic on the group's `createdAt`, then a dedicated
`AddToGroupCommand` that always sent its own "🎉 Great to see everybody"
message. A real walkthrough screenshot showed why the second one was
still wrong: on a genuine add, the bot posted *three* onboarding messages
back to back (`welcomeNewGroup`'s full welcome, `AddToGroupCommand`'s
greeting, then `StartCommand`'s full welcome again once the user typed
`/start`). `AddToGroupCommand` is deleted; the deep-link case is now a
no-op, inlined as a guard in `BotApplication`'s `"start"` dispatch
(Telegram always sends the literal command word `start` for this deep
link — there's no way to make it `/add` at the wire level). `welcomeNewGroup`
is the sole source of the "bot just joined" message; see item 2 for why
typed `/start` no longer repeats it.

### 6. Clean up stale bot messages when a new one of the same kind is sent

When the bot sends a new welcome/help/commands message, the previous
message of that same kind in that chat should be edited or deleted
instead of left behind. `TelegramApi` already exposes `editMessageText`
and `deleteMessage` (used elsewhere for the split flow), so no new
Telegram-side capability is needed. What's missing is somewhere to
remember "the message ID of the last message of purpose X sent to this
chat" — a small addition, roughly the shape of the existing
group-membership tracking, not a new integration.

## Open questions

- Does cleanup (item 6) apply to every bot message, or only to
  onboarding/help-type messages? Expense confirmations, balances, etc.
  are presumably meant to stay as a record and should not be touched.
- Data shape for "last message per purpose per chat" — new table vs.
  reusing something that exists.

## Out of scope (not raised, noted to bound the next design pass)

- Changing what `/start` does for the *first* group member differently
  from the auto group-join message — only repeat visits are in question.
- Any change to how `/split`, `/settle`, etc. actually behave — this is
  purely about onboarding and command-discovery messaging.
