# PR walkthrough: self-service split entry & durable flow state

Branch `self-declared-split` vs `main` — 30 files, +3554/−149.
Spec: `docs/superpowers/specs/2026-09-04-self-service-split-entry-design.md`.
Plan: `docs/superpowers/plans/2026-09-04-self-service-split-entry.md`.

Three changes, one enabling the next:

1. **Durable state** — `SplitStateStore`'s in-memory map becomes a SQLite table (`split_flow_state`).
2. **Many flows per chat** — lookups key off a *message id*, not just the chat, so concurrent `/split`s coexist.
3. **Self-service entry** — participants answer their own amount; `/expenses pending` finds a flow that scrolled away.

---

## 1. Where state lives: before → after

```mermaid
flowchart LR
    subgraph before["BEFORE (main)"]
        direction TB
        B1["CommandRouter"] --> B2["SplitStateStore"]
        B2 --> B3["MutableMap&lt;chatId, SplitState&gt;<br/>in-memory, 1 flow per chat<br/>lost on restart"]
    end

    subgraph after["AFTER (this PR)"]
        direction TB
        A1["CommandRouter"] --> A2["SplitStateStore<br/>(now suspend fns)"]
        A2 --> A3["SplitFlowStateRepository<br/>(interface, storage module)"]
        A3 --> A4["ExposedSplitFlowStateRepository"]
        A4 --> A5[("split_flow_state<br/>SQLite, V3 migration<br/>N flows per chat<br/>survives restart")]
    end

    before -.->|"replaced by"| after
```

New in `storage/`:

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

```mermaid
flowchart LR
    subgraph old["main"]
        O1["get(chatId): SplitState?"]
        O2["set(chatId, state)"]
        O3["clear(chatId)"]
    end
    subgraph new["this PR — all suspend"]
        N1["find(chatId, messageId): SplitState?"]
        N2["set(chatId, state)<br/>upsert keyed by state.promptMessageId"]
        N3["clear(chatId, state)<br/>deletes that one flow"]
        N4["listAll(chatId): List&lt;SplitState&gt;<br/>test introspection"]
        N5["listOpenSplits(groupId): List&lt;PendingSplit&gt;<br/>ENTERING_AMOUNTS only"]
    end
    O1 --> N1
    O2 --> N2
    O3 --> N3
```

`SplitState` gained `val promptMessageId` on the sealed interface, so `set`/`clear` can derive the row
key from any variant.

## 4. Flow lifecycle, now N-per-chat

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

## 5. Who may do what (the actual behaviour change)

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

| Action | main | this PR |
| --- | --- | --- |
| Equal / Exact / Confirm / Cancel | invoker | invoker (unchanged) |
| `pick` someone else's row | invoker | invoker (unchanged) |
| `pick` **my own** row | invoker only | invoker **or that participant** |
| Reply with an amount | invoker only | invoker **or the pending participant** |
| Enter from `/expenses pending` | — | **any participant of that flow** |

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

## 7. The slot-stealing race (last two commits)

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

## 8. Observability & dev-loop extras

Not behavioural, but most of the noise in `CommandRouter.kt`:

- `println` per dispatched command / callback / reply, plus explicit lines for the three ignore paths
  ("unknown command", "non-reply text", "reply to untracked message").
- `PollLoop` now prints a stack trace alongside the error message.
- `scripts/seed-test-members.sh <db>` inserts `@alice`, `@bobby`, `@carol` into the single group in a
  manual-test SQLite file (idempotent, guards on missing db / 0 groups / >1 group).
- README: the seeding step, and the gotcha that a relative `SPLIT_DB_PATH` resolves against
  `telegram/`, not the repo root.
- `/expenses pending` added to `HELP_TEXT`; the participants prompt now tells the invoker that
  unrecognized people must `/start` the bot first.
- detekt baseline: two new `TooManyFunctions` entries (`SplitFlowCallbackHandler`,
  `SplitFlowFormatting.kt`) and some `MaxLineLength` churn in specs.

## 9. Where the implementation diverged from the spec

Worth knowing if you read the spec first:

| Spec said | Shipped |
| --- | --- |
| `id TEXT PRIMARY KEY` generated key + `created_at` | composite PK `(chat_id, prompt_message_id)`, no `created_at` |
| indexes on `chat_id` and `(group_id, stage)` | one index on `(group_id, state_type)` |
| `listOpen(groupId)` | `listOpenSplits(groupId)`, with the `ENTERING_AMOUNTS` filter in Kotlin, not SQL |
| relax `pick` for self only | plus a whole new `pending:enter:` callback path |
| — | the third-party displacement notice (found while testing, added in the last two commits) |

Tests: +861/−98 lines across `ExposedSplitFlowStateRepositorySpec` (new), `SplitStateStoreSpec`,
`SplitFlowSpec`, `SplitFlowCallbackHandlerSpec`, `SplitFlowReplyHandlerSpec`,
`SplitFlowFormattingSpec`, `ExpensesCommandSpec`, `SplitDraftReplyHandlerSpec`.

One layering note: `telegram`'s `SplitStateStore` now imports `split.storage` types directly
(`SplitFlowStateRow`/`Repository`), where the other repository interfaces live in `core`.
