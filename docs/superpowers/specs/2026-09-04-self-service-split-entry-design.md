# Self-service split entry & durable split-flow state — design

## Purpose

Today's `/split` Exact flow (merged in PR #1, `SplitFlowStarter`/`SplitFlowCallbackHandler`/
`SplitFlowReplyHandler`) only lets the *invoker* type in every participant's amount. That fits
"I know what everyone owes." It doesn't fit the case this spec is for: a payer who has no idea
who ordered what, wants to send a photo of the receipt, and just needs the declared amounts to
add up to the total. This spec lets participants answer for themselves, makes the in-flight
state durable (so a bot restart doesn't silently drop whoever hasn't replied yet across what can
now be an hours-long window), and adds a way to find and answer a pending split without
scrolling back to find the original message.

No new domain concept is introduced: `resolveExactSplit` (already fully tested) remains the only
finalization path, and the resulting `Expense` is `SplitType.EXACT` exactly as it is today. Every
change here is in the `telegram` adapter and a new `storage` table.

## Current state (as merged, not as originally spec'd)

- `SplitStateStore` is a single in-memory `MutableMap<ChatId, SplitState>`. `SplitState` is
  sealed: `PendingSplitDraft` (the wizard when `/split` is missing args) and `PendingSplit`
  (mode choice → amount entry).
- `PendingSplit.ENTERING_AMOUNTS` tracks exactly one active slot at a time
  (`pendingParticipantId` / `pendingPromptMessageId`). Auto-advance walks the participant list;
  tapping a participant's row in the table (`pick`) jumps to them out of order and pauses
  auto-advance. This `pick` mechanic is already today's only correction path — the invoker
  redoes an entry by tapping that row again.
- The only invoker-vs-everyone-else check is `SplitFlowReplyHandler.kt:16`:
  `if (context.memberId != flow.invokerId) return`. A parallel check gates every callback in
  `SplitFlowCallbackHandler.kt:34`.
- `Confirm` re-validates via `resolveExactSplit`; a sum mismatch shows an alert and leaves the
  flow open, untouched — this is already exactly the "reject and keep open" behavior wanted here.
- `SplitStateStore.get(chatId)` is a singleton lookup: **at most one flow can be in progress per
  chat**, ever. Starting a new `/split` overwrites it (orphaning any old prompt message).

## What's changing

### 1. Fold self-entry into Exact — no third button

Relax two checks instead of adding a mode:

- `SplitFlowReplyHandler`: `context.memberId !in setOf(flow.invokerId, pendingParticipantId)` →
  return, instead of checking only `invokerId`.
- `pick` (`SplitFlowCallbackHandler.pickParticipant`): today gated invoker-only by the handler's
  top-level check. Add: a participant may also pick *their own* row (jump their own slot,
  whether to answer early or to correct an earlier answer) regardless of who started the split.
  Picking someone *else's* row stays invoker-only.

Everything else — the table, auto-advance, `Confirm`/`Cancel` staying invoker-only, the mismatch
handling — is unchanged. The payer can still fill in everyone (today's behavior), or a
participant can answer their own slot whenever it's convenient, in any mix.

### 2. Durable split-flow state

One new table, `split_flow_state`, replaces `SplitStateStore`'s in-memory map — covering
`PendingSplitDraft` and `PendingSplit` both, via a `state_type` discriminator column. This is a
single table, not one-per-variant and not normalized out into a per-participant child table:
unlike `Expense`/`ExpenseShare` (queried independently — balances, per-member history), a flow's
participant list and entered-amounts map are never queried on their own anywhere; every access
reads the whole flow, mutates it, and writes the whole thing back (`flow.copy(...)` then
`store.set(...)`), exactly the shape of the in-memory map today. `participant_ids`,
`amounts_entered`, and `mention_usernames` are stored as JSON text columns, using the
kotlinx.serialization already in place for the Telegram DTOs — no new dependency, no departure
from typed columns for anything that's actually queried (`chat_id`, `group_id`, `stage`, the
tracked message ids).

```sql
-- V3__add_split_flow_state.sql
CREATE TABLE split_flow_state (
    id                    TEXT PRIMARY KEY,
    chat_id               INTEGER NOT NULL,
    group_id              TEXT NOT NULL REFERENCES group_(id),
    state_type            TEXT NOT NULL,        -- DRAFT | SPLIT
    invoker_id            TEXT NOT NULL REFERENCES member(id),
    prompt_message_id     INTEGER NOT NULL,     -- this flow's stable identity within the chat
    -- Common to both (description/amount/currency are known before a flow reaches PendingSplit,
    -- and are already partially known while still a PendingSplitDraft)
    description           TEXT,
    amount_cents          INTEGER,
    currency              TEXT,
    -- PendingSplitDraft-only fields (null for SPLIT)
    awaiting              TEXT,
    split_type_hint       TEXT,
    mention_usernames     TEXT,                 -- JSON list<String>, DRAFT only
    exact_amounts         TEXT,                 -- JSON list<cents>, DRAFT only
    -- PendingSplit-only fields (null for DRAFT)
    stage                 TEXT,                 -- CHOOSING_MODE | ENTERING_AMOUNTS
    participant_ids       TEXT,                 -- JSON list<MemberId>
    amounts_entered       TEXT,                 -- JSON map<MemberId, cents>
    actions_message_id    INTEGER,
    pending_participant_id     TEXT,
    pending_prompt_message_id  INTEGER,
    pending_is_auto_advance    INTEGER,
    created_at            TEXT NOT NULL
);
CREATE INDEX split_flow_state_chat_idx ON split_flow_state(chat_id);
CREATE INDEX split_flow_state_group_stage_idx ON split_flow_state(group_id, stage);
```

`amount`/`amounts_entered`/`exact_amounts` follow the existing `amountCents: Long` convention
(`ExpenseTable`), not raw `BigDecimal`.

### 3. Multiple concurrent flows per chat

Confirmed in this conversation: `/expenses pending` should be a real list, which means the
current one-flow-per-chat ceiling has to go — otherwise one slow self-entered split blocks the
whole group from starting any other `/split` for however long it's open. `split_flow_state` has
no unique constraint on `chat_id` alone; `id` (a generated key, matching every other table's
convention) is the primary key, `prompt_message_id` is that flow's stable identity within the
chat for message-id matching, and `chat_id`/`group_id` are plain indexed lookup columns.

`SplitStateStore`'s interface changes shape accordingly (this is a real diff, not zero-cost):

- `get(chatId)` → `find(chatId, messageId): SplitState?` — scans this chat's flows for the one
  whose `promptMessageId`/`actionsMessageId`/`pendingPromptMessageId` matches. Replaces the
  `isTrackedReply` lookup, `SplitFlowCallbackHandler`'s manual `messageId` check
  (`CommandRouter.kt:133`, `SplitFlowCallbackHandler.kt:30`), *and* `BotApplication`'s
  `replyHandler` dispatch lambda (`BotApplication.kt:126-132`), which today calls
  `splitStateStore.get(context.chatId)` with no message-id check at all — that only worked
  because there was at most one flow per chat; it must switch to
  `find(context.chatId, context.replyToMessageId)` now that there can be several.
- `set(chatId, state)` stays the same shape — the row to upsert is determined by the state's own
  `promptMessageId`, which callers already have.
- `clear(chatId)` → `clear(chatId, state)` — removes that one flow, not "the" chat's only flow.
- New: `listOpen(groupId): List<PendingSplit>` — flows in `ENTERING_AMOUNTS`, for
  `/expenses pending`.

Starting a new `/split` while others are open in the same chat now just adds a flow alongside
them, rather than silently orphaning whatever was in progress — a latent rough edge today,
fixed as a side effect.

### 4. `/expenses pending`

New branch on `ExpensesCommand` (it already ignores `context.args`; `/split` already branches
this way internally, so no router change is needed — this is a small, in-command addition).
Lists `store.listOpen(context.groupId)`: description, amount, currency, and per-participant
status (✓ amount, or "—").

Each row gets one **"Enter your amount"** button. This reuses `pick`, not a new mechanism:
`callback_data = "pending:enter:<promptMessageId>"` (that flow's stable id, well under the
64-byte limit) → the handler looks the flow up via `find`, checks the tapper is one of
`participantIds` (any participant, not just whoever's "current" slot it is — same self-row-pick
relaxation as §1), and sends them a **fresh** force-reply prompt at the bottom of the chat,
updating that flow's `pendingParticipantId`/`pendingPromptMessageId` in place. The participant
replies to that fresh prompt exactly as they would to any other amount prompt — no new
reply-matching path. This is the answer to "how do they enter it from the list": the list's job
is only to find a flow that's scrolled out of sight and hand the participant an easy-to-reply-to
message; entering the number is unchanged.

Flows still in `CHOOSING_MODE` aren't listed — there's nothing another participant can do with
those yet (only the invoker picks the mode).

## Error handling

- Non-participant reply/pick on a flow → ignored (reply) or rejected with an alert (pick),
  unchanged from today's non-invoker handling, just re-scoped to "not a named participant of
  this flow" instead of "not the invoker."
- Sum mismatch at `Confirm` → alert with the mismatch, the row is left as-is (`Confirm`/`Cancel`
  are the only two things that delete a `split_flow_state` row); any participant can `pick`
  their own row (via the table or via `/expenses pending`) to correct it. No auto-adjustment.
- `/expenses pending` with nothing open → a plain "No pending splits." message.
- Tapping "Enter your amount" against a flow that was `Confirm`ed or `Cancel`led between the list
  being sent and the tap (someone else closed it out in the meantime) → `find` returns nothing,
  so this hits the same "This split is no longer active" alert every other stale-flow tap already
  falls back to (`SplitFlowCallbackHandler.handle`'s `flow == null` branch). No new message needed,
  but it's the one genuinely new race this feature introduces — worth its own test rather than
  assuming the existing fallback covers it.
- No expiry/TTL on `split_flow_state` rows in this iteration — `Cancel` (invoker-only, unchanged)
  is the only way to close one out early. YAGNI: add expiry only if abandoned flows turn out to
  be an actual problem.

## Testing

Same `FakeTelegramApi` + Kotest `StringSpec` conventions as the rest of the suite — narrative test
names plus self-descriptive fixture/helper extension functions, the same "read as the spec" style
`SplitFlowSpec.kt` already uses (`FlowFixture`, `aliceAndBobbyInGroup()`, `tapExact(...)`, etc.).
The two riskiest areas here — flows no longer being isolated to one-per-chat, and the new
`/expenses pending` entry point touching flows started by other tests' setup — are exactly where
that style earns its keep: each concurrency case should read as a sentence describing the
interleaving, not as a block of raw handler calls the reader has to replay by hand. Where several
cases only differ by which action races which (reply vs. pick vs. confirm vs. cancel, across two
flows), prefer Kotest's table-driven `withData`/`forAll` over copy-pasted near-identical `"..."
{ }` blocks, matching the existing "extract self-descriptive fixtures so specs read as the spec"
practice from PR #1's review pass.

New `ExposedSplitFlowStateRepositorySpec` against a real temp-file SQLite DB (matching
`ExposedExpenseRepositorySpec`'s style). Cases to add/update in `SplitFlowSpec`/a new
`SplitFlowConcurrencySpec` beyond what's already covered:

- A named participant (not the invoker) replies to their own auto-advanced prompt — accepted.
- A named participant `pick`s their own row out of order, before or after auto-advance reaches
  them — accepted; picking *someone else's* row is still invoker-only.
- **Two flows open in the same chat at once, driven concretely**: start flow A (dinner), start
  flow B (drinks) before A is confirmed; reply/pick/confirm/cancel on A and confirm B never
  touches A's row, A's participants' replies never get matched against B's prompts, and vice
  versa. Confirming or cancelling one leaves the other's `pendingParticipantId`/amounts untouched.
- A reply whose `reply_to_message_id` matches a *different* flow's prompt than the one currently
  "pending" in that same flow (e.g. a stale client retry) is not matched — `find` keys off the
  specific message id, not "whatever this chat's most recent flow is."
- `/expenses pending` lists only `ENTERING_AMOUNTS` flows for the group, with correct
  per-participant status, and drops a flow from the list the moment it's confirmed or cancelled.
- Tapping "Enter your amount" from the list for a participant with no amount yet vs. one
  correcting an already-submitted amount.
- Tapping "Enter your amount" for a flow already closed by someone else → "no longer active"
  alert, no crash, no new row resurrected.
- Restart mid-flow: reload `split_flow_state` from the DB, replies against the reloaded flow
  continue to work.

## Out of scope

- Receipt/photo parsing — the picture is context for the humans, not something the bot reads.
- Reminders/nudges to participants who haven't answered yet.
- Expiry/TTL for abandoned flows.
- From `/expenses pending`, only the tapper's own row is ever opened — an invoker wanting to
  enter amounts for several *other* people still needs the original table message. Resurfacing
  the full table from the list is a possible follow-up, not needed for the case this spec is
  for.
