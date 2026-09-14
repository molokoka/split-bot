# Test fixture conventions

How the Telegram module's Kotest fixtures are built, and why — worked out on
`telegram/src/test/kotlin/split/telegram/splitflow/SplitFlowCallbackHandlerSpec.kt`
as the reference implementation. Not yet applied everywhere; see the end of this
file for what's still on the old pattern.

## The shared identity core: `IdentityFixture`

Every fixture in this module needs the same four things to resolve who's who:
`platformDirectory`, `memberRepository`, `groupRepository`, `resolver`, plus a
`FakeTelegramApi`. `IdentityFixture` (`telegram/src/test/kotlin/split/telegram/IdentityFixture.kt`)
holds that core once; domain-specific fixtures (`CallbackFixture`, and eventually
`DraftFixture`/`ReplyFixture`/`CommandFixture`) extend it and add only what their
handler-under-test needs (`expenseRepository`, `splitStateStore`, the handler itself).

## Building a cast: `personas()`

Before: one hand-written method per combination of people × membership state —
`aliceOnly()`, `aliceAndBob()`, `aliceAndBobInGroup()`, `aliceAndBobbyMemberOnly()`...
Every new persona or combination meant a new method.

After: a chain.

```kotlin
val group = personas().alice().bobby().inGroup()   // both become real group members
val group = personas().alice().bobby().known()     // both resolved, neither is a member
```

`inGroup()`/`known()` return a `GroupOf` with **named** accessors (`group.alice`,
`group.bobby`, `group.groupId`) — never positional destructuring. Positional
(`val (a, b, g) = ...`) would silently swap variables if a chain were reordered;
named accessors can't.

Don't add a fluency connector like `.and()` between calls. This project's ktlint style
force-wraps a call chain to one call per line once it exceeds **3 chained calls after
the first expression** — `personas().alice().bobby().known()` (3 continuations) stays on
one line, `personas().alice().bobby().carol().inGroup()` (4) gets force-wrapped. Adding
`.and()` as a no-op connector burns one of those 3 for nothing, so it flips a chain from
fitting to wrapping for zero semantic gain. If a real chain needs a 4th persona, split
it — `val cast = personas().alice().bobby().carol(); val group = cast.inGroup()` —
rather than let ktlint explode it across 5 lines.

## The fixture remembers "the" group

`IdentityFixture.groupId` is set once by `Personas.inGroup()`/`.known()`. Handler-tap
helpers (`tapEqual`, `pick`, ...) read it directly instead of taking a `groupId`
parameter — do NOT thread `groupId = group.groupId` through every call, it's pure
noise once the fixture already knows it. (Also: check whether the handler under test
even *uses* `context.groupId` before assuming it matters — `SplitFlowCallbackHandler`
doesn't.)

## Never hard-code a participant's position

The production callback data encodes *positions* in a keyboard (`splitPickData(index)`),
but a test should never hand-type that index — it silently drifts out of sync with
the participant list above it, and forces the reader to count to verify the test is
even correct. Derive it instead:

```kotlin
private suspend fun CallbackFixture.pick(participant: MemberId, tappedBy: MemberId, ...) {
    val index = currentFlow()!!.participantIds.indexOf(participant)
    handler.handle(CallbackContext(..., splitPickData(index)))
}
```

Call sites then name the actual person: `pick(participant = group.bobby, tappedBy = group.alice, ...)`.

## Don't vary a value nothing checks

`callbackQueryId` is Telegram's opaque per-tap correlation id. Exactly one test in
the file asserts it's echoed back (proving request/response correlation); everywhere
else it's irrelevant. A few call sites used to override it to `"cbq"` instead of the
shared default `"cbq1"` for no reason — that was drift, not a decision. If nothing
asserts on a value, don't give it a second hard-coded variant; keep the default.

## Naming the scenario-runner wrapper

```kotlin
private suspend fun duringSplitFlow(block: suspend CallbackFixture.() -> Unit) {
    withTestDatabase { db -> CallbackFixture(db).block() }
}
```

This collapses "open a DB, build a fixture, run the block as its receiver" into one
call, and — because `block` is `suspend CallbackFixture.() -> Unit` — every test body
inside it calls fixture members unqualified (`personas()`, `tapEqual(...)`,
`telegramApi`, `currentFlow()`), no `fixture.` prefix anywhere.

Name it after **what the scenario is**, not the plumbing mechanism. `aCallback` and
`withCallbackFixture` were both rejected in review for describing the wrapper's
implementation (a callback handler) rather than the thing under test (an in-progress
split flow someone is tapping buttons on) — `duringSplitFlow` is the name that
survived. Each spec file will want its own domain word here, not a copy-pasted name.

## Asymmetric membership: fall back to `joined()` directly

`Personas.inGroup()` joins *everyone* resolved so far — there's no first-class support
for "alice is a member, bob is merely known." That case is rare (one instance found
across 5 fixtures) and not worth complicating the chain for. Compose it locally instead:

```kotlin
private suspend fun DraftFixture.aliceInGroupWithBobbyKnown(): GroupOf {
    val group = personas().alice().bobby().known()
    joined(group.groupId, listOf(group.alice))
    return group
}
```

## One member, one name: why the second persona is `bobby()`, not `bob()`

First attempt at this doc had *two* foundational personas resolving to the same member
id `"2"` — `bob()` (used by `CallbackFixture`/`ReplyFixture`) and `bobby()` (used by
`DraftFixture`, which parses `@bobby` mentions out of message text). Reviewed and
rejected: one member cannot have two different names depending on which test happens to
resolve it first — that's not "the same person, two labels," it's just an inconsistent
fixture.

The fix isn't arbitrary either. Telegram's real mention regex requires **at least 5
characters** (`@([a-zA-Z][a-zA-Z0-9_]{4,31})`) — `"bob"` (3 chars) can never be
`@mentioned` at all; only `"bobby"` is a username Telegram would actually let exist. So
there's one canonical second persona, `IdentityFixture.bobby()` / `Personas.bobby()` /
`GroupOf.bobby`, used everywhere — including scenarios that never parse a mention from
text, where the exact username doesn't matter but consistency does. Don't reintroduce a
`bob()` alongside it, even for a fixture that "only" needs a second group member.

## Also check whether `groupId` is used at all on the data-builder helpers

`tapEqual`/`pick`/etc. dropped `groupId` because `SplitFlowCallbackHandler` ignores
`CallbackContext.groupId` entirely. The same turned out true for `SplitFlowReplyHandler`
and `SplitDraftReplyHandler` (neither reads `ReplyContext.groupId`) — but *not* for
`SplitExpenseCommand`, which does use `CommandContext.groupId` to fetch the group. Either
way, the *call site* still doesn't need to pass it explicitly: since the fixture already
tracks "the" group via `IdentityFixture.groupId`, flow-builder helpers like
`anEnteringAmountsFlow(invokerId, participantIds, ...)` can read `groupId` directly
instead of taking it as a parameter — whether or not the handler under test happens to
use the field once it's threaded through `ReplyContext`/`CommandContext`.

## Migration status

Done: `SplitFlowCallbackHandlerSpec.kt` (`CallbackFixture`), `CommandRouterSpec.kt`,
`SplitDraftReplyHandlerSpec.kt` (`DraftFixture`), `SplitFlowReplyHandlerSpec.kt`
(`ReplyFixture`).

Still on the old per-file `*Fixture` + hand-written `aliceX()` pattern:

- `telegram/src/test/kotlin/split/telegram/commands/SplitExpenseCommandSpec.kt` (`CommandFixture`)
- `telegram/src/test/kotlin/split/telegram/FlowFixture.kt` — already has its own richer,
  shared, business-verb DSL (`chat()`, `tap()`, `replyToPrompt()`, ...); worth deciding
  whether it should be rebased on `IdentityFixture` or left as is.
