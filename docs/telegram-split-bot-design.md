# Telegram bill-splitting bot — design

## Purpose

A Telegram bot that lets a group of friends log shared expenses, see who
owes whom, and settle up — the core "Splitwise" workflow, run entirely
through Telegram commands and inline buttons, no separate app or web login.

WhatsApp is an explicit future possibility, not part of this build. The
architecture is chosen so a future WhatsApp adapter (via the official
WhatsApp Business Cloud API, which is a plain REST API) can reuse the same
core logic without a rewrite — see "Architecture" below — but no WhatsApp
code is written as part of this spec.

## Architecture

A single Kotlin process, split into two layers:

- **Core domain module** — expenses, balances, debt simplification,
  settle-up. Pure Kotlin, no Telegram types, no I/O framework coupling.
- **Telegram adapter module** — command parsing, inline keyboards, message
  formatting. Calls into the core module; never touches ledger tables
  directly.

The bot connects to Telegram via **long-polling** (no public webhook or
inbound network exposure needed). Storage is **SQLite**, one file on disk.

Bot privacy mode is left **on** (the BotFather default) — the adapter only
ever receives command messages, replies to its own messages, and messages
in a private chat with it, never arbitrary group chatter. Every member
learns their Telegram user ID to the bot by running `/start` (or any other
command) at least once — see "Identity resolution" below.

**Deployment**: see `scaleway-kubernetes-deployment-design.md` (the current
target; this spec predates that decision).

## Data model

Two layers: a **platform mapping layer** that is the only place aware of
Telegram-specific identifiers, and a **core ledger layer** that is fully
platform-agnostic — see `core/src/main/kotlin/split/core/Entities.kt` and
`Repositories.kt` for the actual shape, and `sqlite-persistence-design.md`
for the storage-layer reasoning.

Balances are always scoped to a **(group, currency)** pair. A group that
logs expenses in two currencies effectively has two independent ledgers —
there is no conversion between currencies.

`Expense.splitType` records how the split was *entered*, purely for
display. It plays no role after creation — split resolution happens once,
at creation time, and always produces concrete `ExpenseShare` rows that
all downstream logic (balances, debt simplification) sums, never
inspecting `splitType` again. An equal split that doesn't divide evenly
rounds each share down to the nearest cent, and the leftover cents are
added to the payer's own share, so the payer never "gains" from rounding
(see `core`'s split-resolution tests for the exact behavior).

In the Kotlin domain layer, an `Expense` is modeled as one aggregate object
holding a `shares: List<ExpenseShare>` — the repository is what translates
between this in-memory shape and the two normalized tables
(`Expense` + `ExpenseShare`, joined on `expense_id`) when reading or
writing.

## Identity resolution

The Telegram Bot API cannot resolve an arbitrary `@username` to a numeric
user ID unless the bot has already received a message from that user —
there is no "list all group members" call for regular bots. Two
mitigations, both adopted:

1. **Explicit registration via any command, privacy mode on** — Telegram
   privacy mode stays on (the BotFather default), so the adapter never
   sees arbitrary group chatter — only command messages, replies to its
   own messages, and private-chat messages reach it. It caches
   `username → user_id → member_id` into `PlatformIdentity` the moment
   anyone runs their first command (`/start` is the natural one, but any
   command works, since every command message carries the sender's
   Telegram user ID and username).
2. **Guided participant selection as the primary `/split` flow** —
   entering `/split` with anything missing (description, amount, or
   participants) walks you through it one field at a time rather than
   requiring the full command up front; typed `@mentions` are a fast
   path for people already known. If someone genuinely unknown is
   mentioned, the bot replies that it doesn't recognize them yet and
   names the exact fix: ask them to run `/start` with the bot — rather
   than silently failing or giving a vague "say something in the chat"
   instruction.

## Commands

The full command set and its exact behavior lives in `BOT_COMMANDS`
(`telegram/src/main/kotlin/split/telegram/commands/HelpCommand.kt`) and
each command's own handler + test file, not here.

## Debt simplification (`/settle_suggest`)

Computed on demand, not stored — a pure read over one (group, currency)
ledger; only `/settle` writes a `Settlement` row. The algorithm itself
(`simplifyDebts` in `core`) and its edge cases are covered by
`DebtSimplificationSpec`.

## Out of scope for this build

- WhatsApp integration (deferred; architecture accommodates it later)
- Multi-currency conversion (each currency in a group is its own
  independent ledger, no exchange rates)
- Receipt photo / OCR input
- Natural-language expense entry (commands + buttons only)
- Editing an existing expense (only delete + re-add)
