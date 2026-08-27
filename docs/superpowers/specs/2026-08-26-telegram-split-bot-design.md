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

Bot privacy mode is disabled (via BotFather `/setprivacy` → Disable) so the
adapter passively observes every message in a group it's part of, not just
commands — this is what lets it learn a member's Telegram user ID from any
message they send, not only from an explicit `/start` (see "Identity
resolution" below).

**Deployment**: a small AWS EC2 instance (t4g.micro or similar), the JVM
process managed by `systemd` for auto-restart on crash or reboot, SQLite
file on the instance's EBS volume. Deploys are a build + `systemctl
restart` initially; can be automated later if manual deploys become
friction.

## Data model

Two layers: a **platform mapping layer** that is the only place aware of
Telegram-specific identifiers, and a **core ledger layer** that is fully
platform-agnostic.

```
Platform layer (Telegram-specific)
  PlatformGroupLink   (platform, external_chat_id) → group_id
  PlatformIdentity    (platform, external_user_id)  → member_id

Core layer (platform-agnostic)
  Group           id, default_currency, created_at
  Member          id, display_name
  GroupMember     group_id, member_id            (join table)
  Expense         id, group_id, currency, description, amount,
                   payer_id, split_type, created_by, created_at,
                   deleted_at (soft delete)
  ExpenseShare    expense_id, member_id, share_amount
  Settlement      id, group_id, currency, from_member_id, to_member_id,
                   amount, created_by, created_at, deleted_at
```

Balances are always scoped to a **(group, currency)** pair. A group that
logs expenses in two currencies effectively has two independent ledgers —
there is no conversion between currencies.

`Expense.split_type` (`EQUAL` / `EXACT` / `SHARES`) records how the split
was *entered*, purely for display (e.g. `/list` showing "split equally").
It plays no role after creation — split resolution happens once, at
creation time, and always produces concrete `ExpenseShare` rows:

| Split type | You enter | Resulting `ExpenseShare` rows ($90 example) |
|---|---|---|
| Equal | Select participants: you, Alice, Bob | you 30.00, alice 30.00, bob 30.00 |
| Exact | alice 50.00, bob 40.00 | alice 50.00, bob 40.00 |
| Shares | alice 2 shares, bob 1 share | alice 60.00, bob 30.00 |

An equal split that doesn't divide evenly (e.g. $10 / 3) rounds each share
down to the nearest cent, and the leftover cents are added to the payer's
own share — so the shares always sum exactly to the expense amount and the
payer never ends up "gaining" from the rounding.

All downstream logic (balances, debt simplification) sums `ExpenseShare`
rows and never inspects `split_type`.

In the Kotlin domain layer, an `Expense` is modeled as one aggregate object
holding a `shares: List<ExpenseShare>` — the repository is what translates
between this in-memory shape and the two normalized tables
(`Expense` + `ExpenseShare`, joined on `expense_id`) when reading or
writing.

A visual walkthrough of the schema and of a full `/add` command trace
(which adapter/core call reads or writes which table) is published at:
https://claude.ai/code/artifact/c7aec92c-321b-4284-8b89-2dd036532497

## Identity resolution

The Telegram Bot API cannot resolve an arbitrary `@username` to a numeric
user ID unless the bot has already received a message from that user —
there is no "list all group members" call for regular bots. Two
mitigations, both adopted:

1. **Privacy mode disabled** — the bot passively observes every message in
   a group (not just commands/replies) and caches
   `username → user_id → member_id` into `PlatformIdentity` the first time
   it sees a message from anyone, without requiring an explicit `/start`.
2. **Button-based participant selection as the primary `/add` flow** —
   `/add 90 dinner` with no mentions opens an inline keyboard populated
   from the group's already-known members (`/members`), so you tap who
   was there. Typed `@mentions` still work as a fast path for people
   already known. If someone genuinely unknown is mentioned, the bot
   replies that it doesn't recognize them yet and names the exact fix:
   ask them to run `/start` with the bot — rather than silently failing
   or giving a vague "say something in the chat" instruction.
3. **Placeholder members** — `/members add <name>` registers a member
   with no Telegram identity at all (no `PlatformIdentity` row), for a
   friend who isn't on Telegram or hasn't started the bot yet. This
   needs no data model change: `Member` never required a
   `PlatformIdentity` row to begin with, so a placeholder is just a
   `Member` that one never gets attached. They can be split with like
   anyone else and appear in `/balances`, but the bot can't message
   them directly — settling with them is tracked via `/settle` same as
   anyone, it just isn't backed by a notification to them.

## Commands

**Setup**
- `/start` — registers you with the bot, shows help
- `/currency USD` — set/change this group's default currency

**Logging**
- `/add 90 dinner @alice @bob` — logs a $90 expense you paid, split
  equally among you + mentioned people
- `/add 90 dinner` (no mentions) — opens an inline participant picker
  built from known group members, with a "+ Add someone new" option
  that runs `/members add`
- `/members add <name>` — registers a placeholder member with no
  Telegram account, for splitting with a friend who isn't on the bot
  yet (see "Identity resolution")
- `/add 90 EUR dinner @alice @bob` — explicit currency override for this
  one expense
- Inline **"Change split"** button on the confirmation — switches to
  Exact-amounts or Shares entry via a short follow-up prompt
- `/delete 42` — soft-deletes expense #42 (the original payer, or a
  Telegram group admin as reported by the Bot API's chat-administrators
  list), recalculates balances

**Viewing**
- `/balances` — net balance per person in this group, from your point of
  view (e.g. "Alice owes you $30", "You owe Bob $10"), one block per
  currency if the group has more than one
- `/list` — last 10 expenses with their IDs, for use with `/delete`
- `/members` — who the bot currently recognizes in this group

**Settling**
- `/settle @bob 20` — records that you paid Bob $20
- `/settle_suggest` — runs debt simplification (below) and prints the
  minimal set of `/settle` commands that would zero out the whole group

**Misc**
- `/help` — lists all commands

## Debt simplification (`/settle_suggest`)

Computed on demand, not stored. Standard greedy algorithm over one
(group, currency) ledger:

1. Compute each member's net balance: sum of what they're owed minus sum
   of what they owe, from `ExpenseShare` and `Settlement` rows.
2. Split members into creditors (positive net) and debtors (negative net).
3. Repeatedly match the largest debtor with the largest creditor,
   transfer `min(|debt|, credit)`, reduce both, repeat until all
   balances are at zero (within rounding).

This produces a small suggested set of payments in place of one
transaction per original expense. It's a pure read — running it doesn't
write anything; only `/settle` writes a `Settlement` row.

## Out of scope for this build

- WhatsApp integration (deferred; architecture accommodates it later)
- Multi-currency conversion (each currency in a group is its own
  independent ledger, no exchange rates)
- Receipt photo / OCR input
- Natural-language expense entry (commands + buttons only)
- Editing an existing expense (only delete + re-add)
