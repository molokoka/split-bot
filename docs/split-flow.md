# The split flow

How `/split` works once it needs more than one message: where the in-flight state lives, how an
update is routed back to the flow it belongs to, who is allowed to do what, and how a participant
finds a split that has scrolled out of sight.

The behaviour itself is specified by the tests. `SplitFlowSpec` (under
`telegram/src/test/kotlin/split/telegram/splitflow/`) asserts against a rendering of the resulting
chat, so each test reads as a transcript of what participants see. This document covers the
structure those tests don't show, and the reasoning behind it.

Three properties hold the design together, each enabling the next:

1. **State is durable** — flows live in the `split_flow_state` table, so a restart doesn't drop
   whoever hasn't answered yet.
2. **Many flows per chat** — lookups key off a *message id*, not just a chat, so concurrent
   `/split`s coexist.
3. **Anyone named can answer** — a participant enters their own amount, and `/expenses pending`
   hands them a prompt when the original message is long gone.

---

## 1. Where state lives

```mermaid
flowchart LR
    A1["CommandRouter"] --> A2["SplitStateStore<br/>(suspend)"]
    A2 --> A3["SplitFlowStateRepository<br/>interface, storage module"]
    A3 --> A4["ExposedSplitFlowStateRepository"]
    A4 --> A5[("split_flow_state<br/>SQLite, V3 migration")]
```

This was an in-memory `MutableMap<chatId, SplitState>` until self-entry made flows long-lived: once
a split can sit open for hours waiting on someone, a deploy silently dropping it is no longer an
acceptable failure.

In `storage/`:

| File | Role |
| --- | --- |
| `SplitFlowState.kt` | `SplitFlowStateRow` DTO + `SplitFlowStateRepository` interface + `SplitFlowStateType` |
| `ExposedSplitFlowStateRepository.kt` | Exposed impl: `upsert`, `findByChatAndMessage`, `listByChat`, `delete`, `listSplitsByGroup` |
| `JsonColumns.kt` | encode/decode helpers for the JSON text columns (kotlinx-serialization-json added) |
| `Tables.kt` | `SplitFlowStateTable` |
| `V3__add_split_flow_state.sql` | the migration |

## 2. The table

```mermaid
erDiagram
    group_ ||--o{ split_flow_state : "group_id"
    member ||--o{ split_flow_state : "invoker_id"

    split_flow_state {
        INTEGER chat_id PK "part of composite PK"
        INTEGER prompt_message_id PK "flow identity within the chat"
        TEXT group_id FK
        TEXT state_type "DRAFT | SPLIT — discriminator"
        TEXT invoker_id FK
        TEXT description "both"
        INTEGER amount_cents "both — cents, per ExpenseTable convention"
        TEXT currency "both"
        TEXT awaiting "DRAFT only: DESCRIPTION|AMOUNT|PARTICIPANTS"
        TEXT split_type_hint "DRAFT only"
        TEXT mention_usernames "DRAFT only — JSON list"
        TEXT exact_amounts "DRAFT only — JSON list of cents"
        TEXT stage "SPLIT only: CHOOSING_MODE|ENTERING_AMOUNTS"
        TEXT participant_ids "SPLIT only — JSON list"
        TEXT amounts_entered "SPLIT only — JSON map memberId to cents"
        INTEGER actions_message_id "SPLIT only"
        TEXT pending_participant_id "SPLIT only — whose slot is open"
        INTEGER pending_prompt_message_id "SPLIT only — the force-reply message"
        INTEGER pending_is_auto_advance "SPLIT only"
    }
```

One table for both `SplitState` variants, keyed by `(chat_id, prompt_message_id)`, with
`split_flow_state_group_idx (group_id, state_type)` for `/expenses pending`. Lists and maps are
JSON text — a flow is always read, mutated and written whole, so nothing needs to query inside them.

## 3. Dispatch: from "the chat's flow" to "the flow this message belongs to"

The central mechanical change. Every entry point used to ask "what is this chat doing?"; now it asks
"which flow does this message id belong to?" — and one SQL query answers it.

```mermaid
flowchart TD
    U["Telegram update"] --> R{"CommandRouter"}

    R -->|"/command"| C["handlers[command]"]
    R -->|"callback_query"| CB["SplitFlowCallbackHandler.handle"]
    R -->|"text reply"| T{"isTrackedReply<br/>chatId, replyToMessageId<br/>(now suspend)"}

    T -->|"find() == null"| DROP["ignore<br/>(logged)"]
    T -->|"found"| D{"BotApplication replyHandler:<br/>find(chatId, replyToMessageId)"}
    D -->|"PendingSplitDraft"| DR["SplitDraftReplyHandler"]
    D -->|"PendingSplit"| FR["SplitFlowReplyHandler"]

    CB --> CBF{"data startsWith<br/>pending:enter:?"}
    CBF -->|"yes"| PE["enterFromPendingList<br/>find(chatId, id from payload)"]
    CBF -->|"no"| CBN["find(chatId, context.messageId)"]

    find["SplitStateStore.find(chatId, messageId)<br/>↓<br/>WHERE chat_id = ? AND<br/>(prompt_message_id = ?<br/>OR actions_message_id = ?<br/>OR pending_prompt_message_id = ?)"]

    T -.-> find
    D -.-> find
    CBN -.-> find
    PE -.-> find

    style find fill:#eef,stroke:#446
```

The three-way `OR` in `findByChatAndMessage` is what replaced the hand-rolled message-id checks that
used to live in `BotApplication.isTrackedReply`, `SplitFlowCallbackHandler.handle`, and
`SplitDraftReplyHandler`.

### Store API diff

All suspending, since every call reaches the database:

| Method | Purpose |
| --- | --- |
| `find(chatId, messageId)` | the flow this message belongs to, by any of its three tracked ids |
| `set(chatId, state)` | upsert, keyed by the state's own `promptMessageId` |
| `clear(chatId, state)` | remove that one flow, not the chat's only flow |
| `listAll(chatId)` | every flow open in the chat — test introspection |
| `listOpenSplits(groupId)` | `ENTERING_AMOUNTS` flows, for `/expenses pending` |

`promptMessageId` is declared on the sealed `SplitState` interface, so `set` and `clear` derive the
row key from any variant without caring which one they were handed.

## 4. Flow lifecycle

```mermaid
stateDiagram-v2
    [*] --> PendingSplitDraft: "/split with missing args"
    [*] --> CHOOSING_MODE: "/split 90 dinner @alice @bobby"

    state PendingSplitDraft {
        [*] --> DESCRIPTION
        DESCRIPTION --> AMOUNT: reply
        AMOUNT --> PARTICIPANTS: reply
        PARTICIPANTS --> [*]: mentions resolved
        note right of DESCRIPTION
            each step now clear()s the old row
            before set()ting the new prompt id —
            the prompt id IS the row key
        end note
    }

    PendingSplitDraft --> CHOOSING_MODE: flowStarter.start()

    state "PendingSplit" as PS {
        CHOOSING_MODE --> ENTERING_AMOUNTS: tap Exact
        CHOOSING_MODE --> Confirmed: tap Equal
        ENTERING_AMOUNTS --> ENTERING_AMOUNTS: reply with amount / pick a row / pending:enter
        ENTERING_AMOUNTS --> Confirmed: tap Confirm, sums match
        ENTERING_AMOUNTS --> ENTERING_AMOUNTS: tap Confirm, mismatch → alert, row untouched
    }

    Confirmed --> [*]: expense written, row deleted
    PS --> Cancelled: tap Cancel (invoker only)
    Cancelled --> [*]: row deleted

    note left of PS
        Only ENTERING_AMOUNTS flows
        appear in /expenses pending
    end note
```

Starting a second `/split` in a chat no longer orphans the first — it inserts a second row.

## 5. Who may do what

The single top-of-handler `memberId != flow.invokerId` gate is gone, replaced by per-action checks.

```mermaid
flowchart TD
    A["callback arrives"] --> B{"which action?"}
    B -->|"Equal / Exact / Confirm / Cancel"| I{"requireInvoker"}
    I -->|"no"| IA["alert: Only the person who<br/>started this split can do that."]
    I -->|"yes"| IOK["proceed"]

    B -->|"pick:&lt;memberId&gt; (table row)"| P{"tapper == invoker<br/>OR tapper == that row's member?"}
    P -->|"no"| PA["alert: Only the person who started<br/>this split, or that participant, can do that."]
    P -->|"yes"| ASSIGN["assignPendingParticipant"]

    B -->|"pending:enter:&lt;promptMessageId&gt;"| Q{"tapper in flow.participantIds?"}
    Q -->|"no"| QA["alert: You're not part of this split."]
    Q -->|"yes"| ASSIGN

    R["text reply to the pending prompt"] --> S{"memberId == invoker<br/>OR == pendingParticipantId?"}
    S -->|"no"| SD["ignored"]
    S -->|"yes"| SOK["amount recorded"]

    style ASSIGN fill:#efe,stroke:#484
```

| Action | Who may do it |
| --- | --- |
| Equal / Exact / Confirm / Cancel | the invoker only |
| `pick` someone else's row | the invoker only |
| `pick` your own row | the invoker, or that participant |
| Reply with an amount | the invoker, or the participant whose slot is open |
| Enter from `/expenses pending` | any participant of that flow |

The asymmetry is deliberate: deciding the split and closing it out stay with whoever started it,
while answering for yourself never needs their involvement.

## 6. `/expenses pending`

```mermaid
sequenceDiagram
    actor Alice as "Alice (participant)"
    participant TG as Telegram
    participant Cmd as ExpensesCommand
    participant Store as SplitStateStore
    participant DB as split_flow_state
    participant CB as SplitFlowCallbackHandler
    participant RH as SplitFlowReplyHandler

    Alice->>TG: "/expenses pending"
    TG->>Cmd: CommandContext, args = "pending"
    Cmd->>Store: listOpenSplits(groupId)
    Store->>DB: "WHERE group_id = ? AND state_type = 'SPLIT'"
    DB-->>Store: rows
    Store-->>Cmd: "PendingSplit list, filtered to ENTERING_AMOUNTS"
    Cmd->>TG: "table: Split | Status (per-participant amount or —)<br/>+ one button per flow: pending:enter:<promptMessageId>"

    Alice->>TG: "taps Enter your amount — dinner"
    TG->>CB: "callback data = pending:enter:7"
    CB->>Store: find(chatId, 7)
    alt flow gone (confirmed/cancelled meanwhile)
        CB->>TG: "alert: This split is no longer active."
    else Alice not a participant
        CB->>TG: "alert: You're not part of this split."
    else ok
        CB->>TG: "fresh force-reply prompt at the bottom of the chat"
        CB->>Store: "set(pendingParticipantId = Alice, pendingPromptMessageId = new, autoAdvance = false)"
    end

    Alice->>TG: "replies 30 to that prompt"
    TG->>RH: "ReplyContext (routed via find on replyToMessageId)"
    RH->>Store: "set(amountsEntered + Alice→30)"
```

`buildPendingSplitsMessage` / `pendingSplitsKeyboard` / `pendingSplitEnterData` are the new formatting
functions; `PENDING_ENTER_PREFIX = "pending:enter:"`. Empty case: a plain "No pending splits." with no
keyboard. Entering the amount itself reuses the existing prompt-and-reply path — the list's only job
is to hand you a message you can reply to.

## 7. The slot-stealing race

`ENTERING_AMOUNTS` still has exactly one open slot. Two people can race for it, and the fix is about
not destroying a bystander's prompt:

```mermaid
flowchart TD
    START["assignPendingParticipant(context, flow, memberId)"] --> D{"flow.pendingParticipantId exists<br/>AND != tapper<br/>AND != the member being assigned?"}
    D -->|"yes — a genuine third party held the slot"| N["keep their prompt message<br/>send: '@displaced, someone else is entering<br/>their amount now — tap Enter your<br/>amount again when you're ready.'"]
    D -->|"no — it was the tapper's own / the same slot"| DEL["delete the stale pending prompt"]
    N --> P["send fresh prompt to memberId"]
    DEL --> P
    P --> S["set(pendingParticipantId = memberId,<br/>pendingPromptMessageId = new,<br/>pendingIsAutoAdvance = false)"]
```

Previously the old pending prompt was deleted unconditionally, which silently yanked the message a
third party was about to reply to.

## 8. Working on this

`CommandRouter` logs every dispatched command, callback and reply, and each of the three ways an
update is dropped — unknown command, non-reply text, reply to an untracked message. When a tap
appears to do nothing, that log says which of those happened.

For manual testing against real Telegram, seed a group first, since most of this needs more than
one member and you are the only real account in your own test chat:

```bash
./gradlew :telegram:seed --args="<chatId> members"
```

In-flight flows are deliberately not seedable — a `split_flow_state` row is keyed by Telegram
message ids, so a fabricated one points at messages that were never sent, leaving a row with no
table to look at and no buttons to tap. Seed the members, then start the split by hand.

## 9. Why it's built this way

The decisions that aren't recoverable from reading the code.

**Self-entry is folded into Exact, not a third mode.** A participant answering for themselves and
the payer filling in everyone produce the same expense by the same path — `resolveExactSplit` stays
the only finalization route, and the result is a `SplitType.EXACT` expense either way. Adding a
"self-service" mode would have meant a second way to reach an identical outcome, and a button
everyone has to understand before they can use either.

**One table with a `state_type` discriminator, not a normalized schema.** Unlike
`Expense`/`ExpenseShare`, which are queried independently for balances and per-member history, a
flow's participant list and entered amounts are never read on their own. Every access loads the
whole flow, mutates it, and writes it back — the same shape as the in-memory map it replaced. So
`participant_ids`, `amounts_entered` and `mention_usernames` are JSON text, and only the fields
actually queried — `chat_id`, `group_id`, `state_type`, the tracked message ids — are typed columns.

**Several flows per chat.** `/expenses pending` is only worth having if there can be more than one
thing pending. The old one-flow-per-chat ceiling also meant a second `/split` silently orphaned the
first, which was a latent bug rather than a feature.

**A sum mismatch rejects and leaves the flow open.** Confirm re-validates and shows the mismatch
without touching the row; the split is only ever closed by a successful Confirm or by Cancel.
Auto-adjusting someone's declared amount to make the total work would quietly put words in their
mouth, which is exactly what this flow exists to avoid.

**Deliberately absent.** No receipt parsing — the photo is context for the humans, not input for the
bot. No reminders or nudges to participants who haven't answered. No expiry on abandoned flows:
Cancel is the only way to close one early, and a TTL is worth adding only if abandoned rows turn out
to be a real problem.

**One layering wrinkle worth knowing.** `SplitStateStore` imports `SplitFlowStateRow` and
`SplitFlowStateRepository` from `split.storage` directly, whereas the other repository interfaces
the adapter uses (`ExpenseRepository`, `MemberRepository`, `PlatformDirectory`) live in `core`.
Flow state is adapter-level rather than domain state, so it doesn't belong in `core` — but the
inconsistency is real, and worth resolving if a second adapter ever appears.
