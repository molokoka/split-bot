# Exact-amount split — design

## Purpose

`/split` currently only supports splitting an expense equally among its
participants (`resolveEqualSplit`). The `core` module already implements
`resolveExactSplit` (each participant's exact share, validated to sum to the
expense total) and `resolveSharesSplit`, both fully tested — the gap is
entirely in the Telegram adapter, which never calls anything but
`resolveEqualSplit`. This spec adds an interactive way to enter exact
per-person amounts. Shares-based splitting stays out of scope (not asked
for; `resolveSharesSplit` is unaffected and trivial to wire up later using
the same mechanics).

## Diagrams

### Diagram 1 — the guided flow end to end

```mermaid
flowchart TD
    Start["/split invoked"] --> Complete{"description, amount,\nand mentions all given?"}
    Complete -- no --> Draft["PendingSplitDraft\nasks for the missing fields,\none reply at a time"]
    Draft -->|field answered, one still missing| Draft
    Draft -->|all fields collected| Starter
    Complete -- yes --> Starter["SplitFlowStarter.start"]
    Starter --> Inline{"exact amounts\ngiven inline?"}
    Inline -- yes --> Exact1["create EXACT expense\nimmediately"]
    Inline -- no --> Hint{splitTypeHint}
    Hint -- EQUAL --> Equal1["create EQUAL expense\nimmediately"]
    Hint -- EXACT --> Entering["PendingSplit: ENTERING_AMOUNTS\n(mode choice skipped)"]
    Hint -- none --> Choosing["PendingSplit: CHOOSING_MODE\nEqual / Exact buttons"]
    Choosing -->|tap Equal| Equal1
    Choosing -->|tap Exact| Entering
    Entering --> Loop["prompt each participant,\nauto-advancing — see Diagram 2"]
    Loop -->|Confirm, sum matches total| Exact2["create EXACT expense"]
    Loop -->|Cancel| Cancelled["flow cleared,\nno expense created"]
```

### Diagram 2 — stages and the auto-advance flag

```mermaid
stateDiagram-v2
    [*] --> CHOOSING_MODE
    CHOOSING_MODE --> [*]: tap Equal — expense created
    CHOOSING_MODE --> ENTERING_AMOUNTS: tap Exact

    state ENTERING_AMOUNTS {
        [*] --> AutoAdvancing
        AutoAdvancing --> AutoAdvancing: valid reply, unfilled\nparticipants remain
        AutoAdvancing --> NothingPending: valid reply, every\nparticipant now filled
        AutoAdvancing --> ManuallyPinned: tap a participant button\n(pendingIsAutoAdvance = false)
        ManuallyPinned --> NothingPending: valid reply\n(auto-advance suppressed)
        ManuallyPinned --> ManuallyPinned: tap a different\nparticipant button
        NothingPending --> ManuallyPinned: tap a participant button\n(edit before confirming)
    }
    ENTERING_AMOUNTS --> [*]: Confirm — sum matches total
    ENTERING_AMOUNTS --> [*]: Cancel
```

## Out of scope

- Shares-based splitting UX (`resolveSharesSplit` already exists in
  `core`, unused by the adapter; same mechanics could wire it up later).
- Multi-payer expenses / bill-photo attachment — raised as a possible
  second iteration during design, then dropped; not part of this spec.
- Persisting in-flight split state across a process restart.
