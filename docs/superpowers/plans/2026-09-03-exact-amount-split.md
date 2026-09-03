# Exact-Amount Split Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let `/split` offer an interactive "Exact amounts" mode — alongside the existing immediate equal split — where the invoker picks each participant via inline buttons and replies with their amount, using the Bot API's whole-keyboard `force_reply` and per-button `disabled` fields.

**Architecture:** `/split` now starts a flow instead of creating an expense directly: it sends an Equal/Exact choice keyboard and stores a `PendingSplit` in a new in-memory `SplitFlowStore` (keyed by chat id — `PollLoop` processes updates one at a time, so no locking is needed). `CommandRouter` gains two more dispatch branches — Telegram `callback_query` updates and plain-text replies to a tracked message — routed to a `SplitFlowCallbackHandler` and `SplitFlowReplyHandler` respectively. Both call the already-implemented, already-tested `resolveEqualSplit`/`resolveExactSplit` in `core` unchanged; only the Telegram adapter layer is new.

**Tech Stack:** No new dependencies — this stays within the `telegram` module's existing Kotlin 2.4.0/Ktor 3.5.2/kotlinx-serialization 1.11.0/Kotest 5.9.1 stack.

**Spec:** `docs/superpowers/specs/2026-09-03-exact-amount-split-design.md`

## Global Constraints

- No new Gradle dependencies — everything is built on the `telegram` module's existing Ktor client, kotlinx-serialization, and Kotest setup.
- Follow this module's existing conventions: explicit imports (except `HttpTelegramApi.kt`'s established `io.ktor.*` wildcard exception), `suspend fun` for anything doing I/O, Kotest `StringSpec` tests using a real temp-file SQLite database (`withTestDatabase`) for anything touching repositories and `FakeTelegramApi` for anything touching Telegram — no mocking framework.
- Every outgoing message stays `parse_mode: HTML`; any user-controlled text (display names, the expense description) interpolated into a plain (non-rich) message must go through `escapeHtml` from `MessageFormatting.kt`, exactly as existing commands already do.
- `callback_data` values must stay well under Telegram's 64-byte limit — encode only a short action code plus a participant *index* into the flow's participant list, never a raw `MemberId`.
- kotlinx-serialization in this project omits a field from outgoing JSON when it's left at its declared default (this project's `Json {}` does not set `encodeDefaults = true`) — a `null`-defaulted optional field like `reply_markup` is simply absent from the request body when not supplied, not sent as explicit `null`. Test assertions on request JSON must reflect this.

---

### Task 1: Bot API DTOs — callback queries, replies, inline keyboards

**Files:**
- Modify: `telegram/src/main/kotlin/split/telegram/TelegramDtos.kt`
- Test: `telegram/src/test/kotlin/split/telegram/TelegramDtosSpec.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces: `TgCallbackQuery(id: String, from: TgUser, message: TgMessage? = null, data: String? = null)`; `TgMessage` gains `replyToMessage: TgMessage? = null`; `TgUpdate` gains `callbackQuery: TgCallbackQuery? = null`; `InlineKeyboardButton(text: String, callbackData: String? = null, disabled: Boolean? = null)`; `InlineKeyboardMarkup(inlineKeyboard: List<List<InlineKeyboardButton>>, forceReply: Boolean? = null)`. All later tasks in this plan depend on these.

- [ ] **Step 1: Write the failing tests**

Append to `telegram/src/test/kotlin/split/telegram/TelegramDtosSpec.kt`, inside the existing `StringSpec({ ... })` block, after the last test:

```kotlin
    "deserializes an update with a callback_query" {
        val body = """
            {
              "update_id": 5,
              "callback_query": {
                "id": "cbq1",
                "from": {"id": 1, "first_name": "Alice", "username": "alice_w"},
                "message": {
                  "message_id": 42,
                  "chat": {"id": -100, "type": "group"}
                },
                "data": "split:mode:exact"
              }
            }
        """.trimIndent()

        val parsed = json.decodeFromString(TgUpdate.serializer(), body)

        parsed shouldBe TgUpdate(
            updateId = 5,
            callbackQuery = TgCallbackQuery(
                id = "cbq1",
                from = TgUser(id = 1, firstName = "Alice", username = "alice_w"),
                message = TgMessage(messageId = 42, chat = TgChat(id = -100, type = "group")),
                data = "split:mode:exact",
            ),
        )
    }

    "deserializes a message that's a reply to another message" {
        val body = """
            {
              "message_id": 7,
              "from": {"id": 1, "first_name": "Alice"},
              "chat": {"id": -100, "type": "group"},
              "text": "50",
              "reply_to_message": {
                "message_id": 3,
                "chat": {"id": -100, "type": "group"}
              }
            }
        """.trimIndent()

        val parsed = json.decodeFromString(TgMessage.serializer(), body)

        parsed shouldBe TgMessage(
            messageId = 7,
            from = TgUser(id = 1, firstName = "Alice"),
            chat = TgChat(id = -100, type = "group"),
            text = "50",
            replyToMessage = TgMessage(messageId = 3, chat = TgChat(id = -100, type = "group")),
        )
    }
```

- [ ] **Step 2: Run tests to verify they fail to compile**

Run: `./gradlew :telegram:test --tests "split.telegram.TelegramDtosSpec"`
Expected: compile error — `TgCallbackQuery` is unresolved, and `TgUpdate`/`TgMessage` have no `callbackQuery`/`replyToMessage` parameters.

- [ ] **Step 3: Add the new/extended DTOs**

In `telegram/src/main/kotlin/split/telegram/TelegramDtos.kt`, replace:

```kotlin
@Serializable
data class TgMessage(
    @SerialName("message_id") val messageId: Long,
    val from: TgUser? = null,
    val chat: TgChat,
    val text: String? = null,
)

@Serializable
data class TgUpdate(
    @SerialName("update_id") val updateId: Long,
    val message: TgMessage? = null,
)
```

with:

```kotlin
@Serializable
data class TgMessage(
    @SerialName("message_id") val messageId: Long,
    val from: TgUser? = null,
    val chat: TgChat,
    val text: String? = null,
    // Telegram nests this one level deep in practice (a reply's own reply_to_message is
    // never populated), but the type is self-referential to mirror the real API shape.
    @SerialName("reply_to_message") val replyToMessage: TgMessage? = null,
)

@Serializable
data class TgCallbackQuery(
    val id: String,
    val from: TgUser,
    val message: TgMessage? = null,
    val data: String? = null,
)

@Serializable
data class TgUpdate(
    @SerialName("update_id") val updateId: Long,
    val message: TgMessage? = null,
    @SerialName("callback_query") val callbackQuery: TgCallbackQuery? = null,
)
```

Then add the outgoing inline-keyboard types anywhere in the same file:

```kotlin
@Serializable
data class InlineKeyboardButton(
    val text: String,
    @SerialName("callback_data") val callbackData: String? = null,
    val disabled: Boolean? = null,
)

// Setting force_reply pops the reply composer, targeted at this message, when any button
// on this keyboard is tapped (Bot API 10.3) — it applies to the whole keyboard, not a
// single button, which is why the exact-split flow uses two separate messages/keyboards
// (see the design spec's "UX overview").
@Serializable
data class InlineKeyboardMarkup(
    @SerialName("inline_keyboard") val inlineKeyboard: List<List<InlineKeyboardButton>>,
    @SerialName("force_reply") val forceReply: Boolean? = null,
)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :telegram:test --tests "split.telegram.TelegramDtosSpec"`
Expected: `BUILD SUCCESSFUL`, all tests pass (3 existing + 2 new).

- [ ] **Step 5: Commit**

```bash
git add telegram/src/main/kotlin/split/telegram/TelegramDtos.kt \
        telegram/src/test/kotlin/split/telegram/TelegramDtosSpec.kt
git commit -m "Add callback_query, reply_to_message, and inline keyboard DTOs"
```

---

### Task 2: `TelegramApi` — keyboards, message editing, callback answers

**Files:**
- Modify: `telegram/src/main/kotlin/split/telegram/TelegramApi.kt`
- Modify: `telegram/src/main/kotlin/split/telegram/TelegramDtos.kt`
- Modify: `telegram/src/main/kotlin/split/telegram/HttpTelegramApi.kt`
- Modify: `telegram/src/test/kotlin/split/telegram/FakeTelegramApi.kt`
- Modify: `telegram/src/test/kotlin/split/telegram/HttpTelegramApiSpec.kt`

**Interfaces:**
- Consumes: `InlineKeyboardMarkup`, `TgCallbackQuery` (Task 1).
- Produces: `TelegramApi.sendMessage(chatId, text, keyboard: InlineKeyboardMarkup? = null): Long`, `sendRichMessage(chatId, richMessage, keyboard: InlineKeyboardMarkup? = null): Long` (both now return the sent message's id — a **breaking return-type change**, from `Unit` to `Long`, on an interface every command already calls; every existing call site still compiles unchanged since Kotlin allows discarding a return value), `editMessageText(chatId, messageId, text, keyboard: InlineKeyboardMarkup? = null)`, `editRichMessage(chatId, messageId, richMessage, keyboard: InlineKeyboardMarkup? = null)`, `answerCallbackQuery(callbackQueryId, text: String? = null, showAlert: Boolean = false)`. All later `telegram` tasks depend on these.

- [ ] **Step 1: Write the failing tests**

Replace the existing `"sendMessage posts chat_id and text as JSON"` and `"sendRichMessage posts chat_id and rich_message as JSON"` tests in `telegram/src/test/kotlin/split/telegram/HttpTelegramApiSpec.kt` — they currently mock a bare `{"ok":true}` response, but `sendMessage`/`sendRichMessage` now need to read the sent message's id back out of the response — and add new tests for the new methods. Replace this block:

```kotlin
    "sendMessage posts chat_id and text as JSON" {
        val (httpClient, requests) = clientReturning("""{"ok":true}""")
        val api = HttpTelegramApi(botToken = "tok", httpClient = httpClient)

        api.sendMessage(chatId = -100, text = "hi")

        requests.single().body.toByteArray().decodeToString() shouldBe """{"chat_id":-100,"text":"hi","parse_mode":"HTML"}"""
    }

    "sendRichMessage posts chat_id and rich_message as JSON" {
        val (httpClient, requests) = clientReturning("""{"ok":true}""")
        val api = HttpTelegramApi(botToken = "tok", httpClient = httpClient)

        api.sendRichMessage(
            chatId = -100,
            richMessage = InputRichMessage(
                blocks = listOf(
                    RichBlockTable(
                        cells = listOf(listOf(RichBlockTableCell("ID", isHeader = true)), listOf(RichBlockTableCell("e1"))),
                        caption = "Last 10 expenses",
                    ),
                ),
            ),
        )

        requests.single().body.toByteArray().decodeToString() shouldBe
            """{"chat_id":-100,"rich_message":{"blocks":[{"type":"table","cells":[[{"text":"ID","is_header":true}],[{"text":"e1"}]],""" +
            """"caption":"Last 10 expenses"}]}}"""
    }
```

with:

```kotlin
    "sendMessage posts chat_id and text as JSON and returns the sent message id" {
        val (httpClient, requests) = clientReturning("""{"ok":true,"result":{"message_id":55,"chat":{"id":-100,"type":"group"}}}""")
        val api = HttpTelegramApi(botToken = "tok", httpClient = httpClient)

        val messageId = api.sendMessage(chatId = -100, text = "hi")

        requests.single().body.toByteArray().decodeToString() shouldBe """{"chat_id":-100,"text":"hi","parse_mode":"HTML"}"""
        messageId shouldBe 55L
    }

    "sendMessage includes reply_markup when a keyboard is given" {
        val (httpClient, requests) = clientReturning("""{"ok":true,"result":{"message_id":1,"chat":{"id":-100,"type":"group"}}}""")
        val api = HttpTelegramApi(botToken = "tok", httpClient = httpClient)

        api.sendMessage(
            chatId = -100,
            text = "How should this be split?",
            keyboard = InlineKeyboardMarkup(
                inlineKeyboard = listOf(listOf(InlineKeyboardButton(text = "Equal", callbackData = "split:mode:equal"))),
            ),
        )

        requests.single().body.toByteArray().decodeToString() shouldBe
            """{"chat_id":-100,"text":"How should this be split?","parse_mode":"HTML",""" +
            """"reply_markup":{"inline_keyboard":[[{"text":"Equal","callback_data":"split:mode:equal"}]]}}"""
    }

    "sendRichMessage posts chat_id and rich_message as JSON and returns the sent message id" {
        val (httpClient, requests) = clientReturning("""{"ok":true,"result":{"message_id":56,"chat":{"id":-100,"type":"group"}}}""")
        val api = HttpTelegramApi(botToken = "tok", httpClient = httpClient)

        val messageId = api.sendRichMessage(
            chatId = -100,
            richMessage = InputRichMessage(
                blocks = listOf(
                    RichBlockTable(
                        cells = listOf(listOf(RichBlockTableCell("ID", isHeader = true)), listOf(RichBlockTableCell("e1"))),
                        caption = "Last 10 expenses",
                    ),
                ),
            ),
        )

        requests.single().body.toByteArray().decodeToString() shouldBe
            """{"chat_id":-100,"rich_message":{"blocks":[{"type":"table","cells":[[{"text":"ID","is_header":true}],[{"text":"e1"}]],""" +
            """"caption":"Last 10 expenses"}]}}"""
        messageId shouldBe 56L
    }

    "editMessageText posts chat_id, message_id, text, and an optional keyboard" {
        val (httpClient, requests) = clientReturning("""{"ok":true}""")
        val api = HttpTelegramApi(botToken = "tok", httpClient = httpClient)

        api.editMessageText(
            chatId = -100,
            messageId = 42,
            text = "Split cancelled.",
            keyboard = InlineKeyboardMarkup(inlineKeyboard = listOf(listOf(InlineKeyboardButton(text = "Confirm", callbackData = "split:confirm")))),
        )

        requests.single().body.toByteArray().decodeToString() shouldBe
            """{"chat_id":-100,"message_id":42,"text":"Split cancelled.","parse_mode":"HTML",""" +
            """"reply_markup":{"inline_keyboard":[[{"text":"Confirm","callback_data":"split:confirm"}]]}}"""
    }

    "editRichMessage posts chat_id, message_id, and rich_message" {
        val (httpClient, requests) = clientReturning("""{"ok":true}""")
        val api = HttpTelegramApi(botToken = "tok", httpClient = httpClient)

        api.editRichMessage(
            chatId = -100,
            messageId = 42,
            richMessage = InputRichMessage(blocks = listOf(RichBlockParagraph("Split cancelled."))),
        )

        requests.single().body.toByteArray().decodeToString() shouldBe
            """{"chat_id":-100,"message_id":42,"rich_message":{"blocks":[{"type":"paragraph","text":"Split cancelled."}]}}"""
    }

    "answerCallbackQuery posts callback_query_id, text, and show_alert" {
        val (httpClient, requests) = clientReturning("""{"ok":true}""")
        val api = HttpTelegramApi(botToken = "tok", httpClient = httpClient)

        api.answerCallbackQuery(callbackQueryId = "cbq1", text = "Only the invoker can do that.", showAlert = true)

        requests.single().body.toByteArray().decodeToString() shouldBe
            """{"callback_query_id":"cbq1","text":"Only the invoker can do that.","show_alert":true}"""
    }

    "answerCallbackQuery omits text and show_alert when not given" {
        val (httpClient, requests) = clientReturning("""{"ok":true}""")
        val api = HttpTelegramApi(botToken = "tok", httpClient = httpClient)

        api.answerCallbackQuery(callbackQueryId = "cbq1")

        requests.single().body.toByteArray().decodeToString() shouldBe """{"callback_query_id":"cbq1"}"""
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :telegram:test --tests "split.telegram.HttpTelegramApiSpec"`
Expected: FAIL — `editMessageText`/`editRichMessage`/`answerCallbackQuery` unresolved, `sendMessage`/`sendRichMessage` don't accept `keyboard` or return a value.

- [ ] **Step 3: Add the new request/response DTOs**

In `telegram/src/main/kotlin/split/telegram/TelegramDtos.kt`, replace:

```kotlin
@Serializable
data class SendMessageRequest(
    @SerialName("chat_id") val chatId: Long,
    val text: String,
    // No default value: kotlinx.serialization omits fields left at their default unless
    // encodeDefaults is set, and this one must always be sent.
    @SerialName("parse_mode") val parseMode: String,
)
```

with:

```kotlin
@Serializable
data class SendMessageRequest(
    @SerialName("chat_id") val chatId: Long,
    val text: String,
    // No default value: kotlinx.serialization omits fields left at their default unless
    // encodeDefaults is set, and this one must always be sent.
    @SerialName("parse_mode") val parseMode: String,
    @SerialName("reply_markup") val replyMarkup: InlineKeyboardMarkup? = null,
)

@Serializable
data class EditMessageTextRequest(
    @SerialName("chat_id") val chatId: Long,
    @SerialName("message_id") val messageId: Long,
    val text: String,
    @SerialName("parse_mode") val parseMode: String,
    @SerialName("reply_markup") val replyMarkup: InlineKeyboardMarkup? = null,
)

@Serializable
data class AnswerCallbackQueryRequest(
    @SerialName("callback_query_id") val callbackQueryId: String,
    val text: String? = null,
    @SerialName("show_alert") val showAlert: Boolean? = null,
)

// Shared by every method that sends or edits a message and needs the resulting message
// back (currently just sendMessage/sendRichMessage — edits don't need it, since the
// caller already knows the message_id it passed in).
@Serializable
data class MessageResponse(
    val ok: Boolean,
    val result: TgMessage,
)
```

Then replace:

```kotlin
@Serializable
data class InputRichMessage(
    val blocks: List<RichBlock>,
)

@Serializable
data class SendRichMessageRequest(
    @SerialName("chat_id") val chatId: Long,
    @SerialName("rich_message") val richMessage: InputRichMessage,
)
```

with:

```kotlin
@Serializable
data class InputRichMessage(
    val blocks: List<RichBlock>,
)

@Serializable
data class SendRichMessageRequest(
    @SerialName("chat_id") val chatId: Long,
    @SerialName("rich_message") val richMessage: InputRichMessage,
    @SerialName("reply_markup") val replyMarkup: InlineKeyboardMarkup? = null,
)

@Serializable
data class EditRichMessageRequest(
    @SerialName("chat_id") val chatId: Long,
    @SerialName("message_id") val messageId: Long,
    @SerialName("rich_message") val richMessage: InputRichMessage,
    @SerialName("reply_markup") val replyMarkup: InlineKeyboardMarkup? = null,
)
```

(`answerCallbackQuery`'s `text`/`show_alert` use nullable-with-null-default rather than a `false` default for `showAlert`, so a call with `showAlert = false` — the common case — omits the field entirely rather than sending `"show_alert":false`; see `answerCallbackQuery`'s implementation in Step 4, which only passes a non-null `Boolean` through when the caller actually asked for `true`.)

- [ ] **Step 4: Extend `TelegramApi` and `HttpTelegramApi`**

In `telegram/src/main/kotlin/split/telegram/TelegramApi.kt`, replace the whole file:

```kotlin
package split.telegram

interface TelegramApi {
    suspend fun getUpdates(offset: Long?, timeoutSeconds: Int): List<TgUpdate>
    suspend fun sendMessage(chatId: Long, text: String, keyboard: InlineKeyboardMarkup? = null): Long
    suspend fun sendRichMessage(chatId: Long, richMessage: InputRichMessage, keyboard: InlineKeyboardMarkup? = null): Long
    suspend fun editMessageText(chatId: Long, messageId: Long, text: String, keyboard: InlineKeyboardMarkup? = null)
    suspend fun editRichMessage(chatId: Long, messageId: Long, richMessage: InputRichMessage, keyboard: InlineKeyboardMarkup? = null)
    suspend fun answerCallbackQuery(callbackQueryId: String, text: String? = null, showAlert: Boolean = false)
    suspend fun getChatAdministrators(chatId: Long): List<TgChatMember>
}
```

In `telegram/src/main/kotlin/split/telegram/HttpTelegramApi.kt`, replace the `sendMessage`/`sendRichMessage` methods and add the three new ones:

```kotlin
    override suspend fun sendMessage(chatId: Long, text: String, keyboard: InlineKeyboardMarkup?): Long {
        val response: MessageResponse = httpClient.post("$baseUrl/bot$botToken/sendMessage") {
            contentType(ContentType.Application.Json)
            setBody(SendMessageRequest(chatId, text, parseMode = "HTML", replyMarkup = keyboard))
        }.body()
        return response.result.messageId
    }

    override suspend fun sendRichMessage(chatId: Long, richMessage: InputRichMessage, keyboard: InlineKeyboardMarkup?): Long {
        val response: MessageResponse = httpClient.post("$baseUrl/bot$botToken/sendRichMessage") {
            contentType(ContentType.Application.Json)
            setBody(SendRichMessageRequest(chatId, richMessage, replyMarkup = keyboard))
        }.body()
        return response.result.messageId
    }

    override suspend fun editMessageText(chatId: Long, messageId: Long, text: String, keyboard: InlineKeyboardMarkup?) {
        httpClient.post("$baseUrl/bot$botToken/editMessageText") {
            contentType(ContentType.Application.Json)
            setBody(EditMessageTextRequest(chatId, messageId, text, parseMode = "HTML", replyMarkup = keyboard))
        }
    }

    override suspend fun editRichMessage(chatId: Long, messageId: Long, richMessage: InputRichMessage, keyboard: InlineKeyboardMarkup?) {
        httpClient.post("$baseUrl/bot$botToken/editRichMessage") {
            contentType(ContentType.Application.Json)
            setBody(EditRichMessageRequest(chatId, messageId, richMessage, replyMarkup = keyboard))
        }
    }

    override suspend fun answerCallbackQuery(callbackQueryId: String, text: String?, showAlert: Boolean) {
        httpClient.post("$baseUrl/bot$botToken/answerCallbackQuery") {
            contentType(ContentType.Application.Json)
            setBody(AnswerCallbackQueryRequest(callbackQueryId, text, showAlert.takeIf { it }))
        }
    }
```

- [ ] **Step 5: Update `FakeTelegramApi`**

Replace `telegram/src/test/kotlin/split/telegram/FakeTelegramApi.kt` entirely:

```kotlin
package split.telegram

class FakeTelegramApi : TelegramApi {
    val sentMessages = mutableListOf<Pair<Long, String>>()
    val sentRichMessages = mutableListOf<Pair<Long, InputRichMessage>>()
    val sentKeyboards = mutableListOf<InlineKeyboardMarkup?>()
    val editedMessages = mutableListOf<Triple<Long, Long, String>>()
    val editedRichMessages = mutableListOf<Triple<Long, Long, InputRichMessage>>()
    val editedKeyboards = mutableListOf<InlineKeyboardMarkup?>()
    val answeredCallbacks = mutableListOf<Triple<String, String?, Boolean>>()
    var chatAdministrators: List<TgChatMember> = emptyList()
    var updatesToReturn: List<TgUpdate> = emptyList()
    var getUpdatesException: Exception? = null
    private var nextMessageId = 1L

    override suspend fun getUpdates(offset: Long?, timeoutSeconds: Int): List<TgUpdate> {
        getUpdatesException?.let { throw it }
        return updatesToReturn
    }

    override suspend fun sendMessage(chatId: Long, text: String, keyboard: InlineKeyboardMarkup?): Long {
        sentMessages += chatId to text
        sentKeyboards += keyboard
        return nextMessageId++
    }

    override suspend fun sendRichMessage(chatId: Long, richMessage: InputRichMessage, keyboard: InlineKeyboardMarkup?): Long {
        sentRichMessages += chatId to richMessage
        sentKeyboards += keyboard
        return nextMessageId++
    }

    override suspend fun editMessageText(chatId: Long, messageId: Long, text: String, keyboard: InlineKeyboardMarkup?) {
        editedMessages += Triple(chatId, messageId, text)
        editedKeyboards += keyboard
    }

    override suspend fun editRichMessage(chatId: Long, messageId: Long, richMessage: InputRichMessage, keyboard: InlineKeyboardMarkup?) {
        editedRichMessages += Triple(chatId, messageId, richMessage)
        editedKeyboards += keyboard
    }

    override suspend fun answerCallbackQuery(callbackQueryId: String, text: String?, showAlert: Boolean) {
        answeredCallbacks += Triple(callbackQueryId, text, showAlert)
    }

    override suspend fun getChatAdministrators(chatId: Long): List<TgChatMember> = chatAdministrators
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew :telegram:test --tests "split.telegram.HttpTelegramApiSpec" --tests "split.telegram.TelegramDtosSpec"`
Expected: `BUILD SUCCESSFUL`.

Run: `./gradlew :telegram:test`
Expected: `BUILD SUCCESSFUL` — every existing command test still passes untouched, since they all ignore `sendMessage`/`sendRichMessage`'s return value.

- [ ] **Step 7: Commit**

```bash
git add telegram/src/main/kotlin/split/telegram/TelegramDtos.kt \
        telegram/src/main/kotlin/split/telegram/TelegramApi.kt \
        telegram/src/main/kotlin/split/telegram/HttpTelegramApi.kt \
        telegram/src/test/kotlin/split/telegram/FakeTelegramApi.kt \
        telegram/src/test/kotlin/split/telegram/HttpTelegramApiSpec.kt
git commit -m "Add inline keyboards, message editing, and callback answers to TelegramApi"
```

---

### Task 3: `SplitFlowStore` — in-memory pending-split state

**Files:**
- Create: `telegram/src/main/kotlin/split/telegram/SplitFlowStore.kt`
- Test: `telegram/src/test/kotlin/split/telegram/SplitFlowStoreSpec.kt`

**Interfaces:**
- Consumes: `MemberId`, `GroupId` (`core`).
- Produces: `enum class SplitFlowStage { CHOOSING_MODE, ENTERING_AMOUNTS }`, `data class PendingSplit(invokerId: MemberId, groupId: GroupId, amount: BigDecimal, currency: String, description: String, participantIds: List<MemberId>, promptMessageId: Long, stage: SplitFlowStage, actionsMessageId: Long? = null, amountsEntered: Map<MemberId, BigDecimal> = emptyMap(), pendingParticipantId: MemberId? = null)`, `class SplitFlowStore { fun get(chatId: Long): PendingSplit?; fun set(chatId: Long, flow: PendingSplit); fun clear(chatId: Long) }`. Tasks 6, 7, 8 depend on these.

- [ ] **Step 1: Write the failing test**

Create `telegram/src/test/kotlin/split/telegram/SplitFlowStoreSpec.kt`:

```kotlin
package split.telegram

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import split.core.GroupId
import split.core.MemberId

class SplitFlowStoreSpec : StringSpec({

    fun aFlow(promptMessageId: Long = 1) = PendingSplit(
        invokerId = MemberId("alice"),
        groupId = GroupId("g1"),
        amount = BigDecimal("90.00"),
        currency = "USD",
        description = "dinner",
        participantIds = listOf(MemberId("alice"), MemberId("bob")),
        promptMessageId = promptMessageId,
        stage = SplitFlowStage.CHOOSING_MODE,
    )

    "returns null when there's no pending flow for a chat" {
        SplitFlowStore().get(-100) shouldBe null
    }

    "returns the flow that was set for a chat" {
        val store = SplitFlowStore()
        val flow = aFlow()

        store.set(-100, flow)

        store.get(-100) shouldBe flow
    }

    "clearing a chat's flow removes it" {
        val store = SplitFlowStore()
        store.set(-100, aFlow())

        store.clear(-100)

        store.get(-100) shouldBe null
    }

    "flows for different chats don't interfere" {
        val store = SplitFlowStore()
        store.set(-100, aFlow(promptMessageId = 1))
        store.set(-200, aFlow(promptMessageId = 2))

        store.get(-100)?.promptMessageId shouldBe 1
        store.get(-200)?.promptMessageId shouldBe 2
    }

    "setting a new flow for a chat replaces the old one" {
        val store = SplitFlowStore()
        store.set(-100, aFlow(promptMessageId = 1))

        store.set(-100, aFlow(promptMessageId = 2))

        store.get(-100)?.promptMessageId shouldBe 2
    }
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :telegram:test --tests "split.telegram.SplitFlowStoreSpec"`
Expected: FAIL — `SplitFlowStore`, `PendingSplit`, `SplitFlowStage` don't exist yet.

- [ ] **Step 3: Write the implementation**

Create `telegram/src/main/kotlin/split/telegram/SplitFlowStore.kt`:

```kotlin
package split.telegram

import split.core.GroupId
import split.core.MemberId
import java.math.BigDecimal

enum class SplitFlowStage { CHOOSING_MODE, ENTERING_AMOUNTS }

data class PendingSplit(
    val invokerId: MemberId,
    val groupId: GroupId,
    val amount: BigDecimal,
    val currency: String,
    val description: String,
    val participantIds: List<MemberId>,
    val promptMessageId: Long,
    val stage: SplitFlowStage,
    val actionsMessageId: Long? = null,
    val amountsEntered: Map<MemberId, BigDecimal> = emptyMap(),
    val pendingParticipantId: MemberId? = null,
)

// Keyed by chat id, not persisted — see the design spec's "Data flow / state" section.
// PollLoop processes updates strictly one at a time (see BotApplication.kt), so a plain
// mutable map needs no synchronization.
class SplitFlowStore {
    private val flows = mutableMapOf<Long, PendingSplit>()

    fun get(chatId: Long): PendingSplit? = flows[chatId]

    fun set(chatId: Long, flow: PendingSplit) {
        flows[chatId] = flow
    }

    fun clear(chatId: Long) {
        flows.remove(chatId)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :telegram:test --tests "split.telegram.SplitFlowStoreSpec"`
Expected: `BUILD SUCCESSFUL`, 5 tests pass.

- [ ] **Step 5: Commit**

```bash
git add telegram/src/main/kotlin/split/telegram/SplitFlowStore.kt \
        telegram/src/test/kotlin/split/telegram/SplitFlowStoreSpec.kt
git commit -m "Add SplitFlowStore for in-memory pending-split state"
```

---

### Task 4: `SplitFlowFormatting` — pure message and keyboard builders

**Files:**
- Create: `telegram/src/main/kotlin/split/telegram/SplitFlowFormatting.kt`
- Test: `telegram/src/test/kotlin/split/telegram/SplitFlowFormattingSpec.kt`

**Interfaces:**
- Consumes: `Member`, `MemberId` (`core`); `plainName`, `formatAmount` (`MessageFormatting.kt`); `InlineKeyboardButton`, `InlineKeyboardMarkup`, `InputRichMessage`, `RichBlockTable`, `RichBlockTableCell` (Task 1).
- Produces: constants `SPLIT_MODE_PROMPT`, `SPLIT_MODE_EQUAL_DATA`, `SPLIT_MODE_EXACT_DATA`, `SPLIT_CANCEL_DATA`, `SPLIT_CONFIRM_DATA`, `SPLIT_PICK_PREFIX`; `fun splitPickData(index: Int): String`; `fun splitModeKeyboard(): InlineKeyboardMarkup`; `fun splitAmountsTable(participantIds: List<MemberId>, amountsEntered: Map<MemberId, BigDecimal>, nameOf: Map<MemberId, Member>, usernames: Map<MemberId, String>, currency: String): InputRichMessage`; `fun splitParticipantKeyboard(participantIds: List<MemberId>, amountsEntered: Map<MemberId, BigDecimal>, nameOf: Map<MemberId, Member>, usernames: Map<MemberId, String>, currency: String): InlineKeyboardMarkup`; `fun splitActionsText(amountsEntered: Map<MemberId, BigDecimal>, amount: BigDecimal, currency: String): String`; `fun splitActionsKeyboard(canConfirm: Boolean): InlineKeyboardMarkup`; `fun splitIsReadyToConfirm(participantIds: List<MemberId>, amountsEntered: Map<MemberId, BigDecimal>, amount: BigDecimal): Boolean`. Tasks 6, 7, 8 depend on all of these.

- [ ] **Step 1: Write the failing test**

Create `telegram/src/test/kotlin/split/telegram/SplitFlowFormattingSpec.kt`:

```kotlin
package split.telegram

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import split.core.Member
import split.core.MemberId

class SplitFlowFormattingSpec : StringSpec({

    val alice = Member(MemberId("alice"), "Alice")
    val bob = Member(MemberId("bob"), "Bob")
    val nameOf = mapOf(alice.id to alice, bob.id to bob)

    "splitModeKeyboard offers Equal and Exact, no force_reply" {
        splitModeKeyboard() shouldBe InlineKeyboardMarkup(
            inlineKeyboard = listOf(
                listOf(
                    InlineKeyboardButton(text = "Equal", callbackData = SPLIT_MODE_EQUAL_DATA),
                    InlineKeyboardButton(text = "Exact", callbackData = SPLIT_MODE_EXACT_DATA),
                ),
            ),
        )
    }

    "splitAmountsTable shows a dash for unfilled amounts and the amount for filled ones" {
        val table = splitAmountsTable(
            participantIds = listOf(alice.id, bob.id),
            amountsEntered = mapOf(alice.id to BigDecimal("50.00")),
            nameOf = nameOf,
            usernames = emptyMap(),
            currency = "USD",
        )

        val rows = (table.blocks.single() as RichBlockTable).cells
        rows shouldBe listOf(
            listOf(RichBlockTableCell("Person", isHeader = true), RichBlockTableCell("Amount", isHeader = true)),
            listOf(RichBlockTableCell("Alice"), RichBlockTableCell("50.00 USD")),
            listOf(RichBlockTableCell("Bob"), RichBlockTableCell("—")),
        )
    }

    "splitParticipantKeyboard disables a participant's button once they have an amount, and sets force_reply" {
        val keyboard = splitParticipantKeyboard(
            participantIds = listOf(alice.id, bob.id),
            amountsEntered = mapOf(alice.id to BigDecimal("50.00")),
            nameOf = nameOf,
            usernames = emptyMap(),
            currency = "USD",
        )

        keyboard shouldBe InlineKeyboardMarkup(
            inlineKeyboard = listOf(
                listOf(InlineKeyboardButton(text = "Alice ✓ 50.00 USD", callbackData = splitPickData(0), disabled = true)),
                listOf(InlineKeyboardButton(text = "Bob", callbackData = splitPickData(1), disabled = false)),
            ),
            forceReply = true,
        )
    }

    "splitActionsText shows the running total against the expense amount" {
        splitActionsText(
            amountsEntered = mapOf(alice.id to BigDecimal("50.00")),
            amount = BigDecimal("90.00"),
            currency = "USD",
        ) shouldBe "Entered 50.00 USD of 90.00 USD"
    }

    "splitActionsKeyboard disables Confirm when canConfirm is false" {
        splitActionsKeyboard(canConfirm = false) shouldBe InlineKeyboardMarkup(
            inlineKeyboard = listOf(
                listOf(
                    InlineKeyboardButton(text = "Cancel", callbackData = SPLIT_CANCEL_DATA),
                    InlineKeyboardButton(text = "Confirm", callbackData = SPLIT_CONFIRM_DATA, disabled = true),
                ),
            ),
        )
    }

    "splitActionsKeyboard enables Confirm when canConfirm is true" {
        val confirmButton = splitActionsKeyboard(canConfirm = true).inlineKeyboard[0][1]
        confirmButton.disabled shouldBe false
    }

    "splitIsReadyToConfirm is false until every participant has an amount" {
        splitIsReadyToConfirm(
            participantIds = listOf(alice.id, bob.id),
            amountsEntered = mapOf(alice.id to BigDecimal("50.00")),
            amount = BigDecimal("90.00"),
        ) shouldBe false
    }

    "splitIsReadyToConfirm is false when amounts are all entered but don't sum to the total" {
        splitIsReadyToConfirm(
            participantIds = listOf(alice.id, bob.id),
            amountsEntered = mapOf(alice.id to BigDecimal("50.00"), bob.id to BigDecimal("30.00")),
            amount = BigDecimal("90.00"),
        ) shouldBe false
    }

    "splitIsReadyToConfirm is true once amounts are all entered and sum to the total" {
        splitIsReadyToConfirm(
            participantIds = listOf(alice.id, bob.id),
            amountsEntered = mapOf(alice.id to BigDecimal("50.00"), bob.id to BigDecimal("40.00")),
            amount = BigDecimal("90.00"),
        ) shouldBe true
    }
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :telegram:test --tests "split.telegram.SplitFlowFormattingSpec"`
Expected: FAIL — none of these functions/constants exist yet.

- [ ] **Step 3: Write the implementation**

Create `telegram/src/main/kotlin/split/telegram/SplitFlowFormatting.kt`:

```kotlin
package split.telegram

import split.core.Member
import split.core.MemberId
import java.math.BigDecimal

const val SPLIT_MODE_PROMPT = "How should this be split?"
const val SPLIT_MODE_EQUAL_DATA = "split:mode:equal"
const val SPLIT_MODE_EXACT_DATA = "split:mode:exact"
const val SPLIT_CANCEL_DATA = "split:cancel"
const val SPLIT_CONFIRM_DATA = "split:confirm"
const val SPLIT_PICK_PREFIX = "split:pick:"

fun splitPickData(index: Int): String = "$SPLIT_PICK_PREFIX$index"

fun splitModeKeyboard(): InlineKeyboardMarkup = InlineKeyboardMarkup(
    inlineKeyboard = listOf(
        listOf(
            InlineKeyboardButton(text = "Equal", callbackData = SPLIT_MODE_EQUAL_DATA),
            InlineKeyboardButton(text = "Exact", callbackData = SPLIT_MODE_EXACT_DATA),
        ),
    ),
)

fun splitAmountsTable(
    participantIds: List<MemberId>,
    amountsEntered: Map<MemberId, BigDecimal>,
    nameOf: Map<MemberId, Member>,
    usernames: Map<MemberId, String>,
    currency: String,
): InputRichMessage {
    val header = listOf("Person", "Amount").map { RichBlockTableCell(text = it, isHeader = true) }
    val rows = participantIds.map { memberId ->
        val amountText = amountsEntered[memberId]?.let { formatAmount(it, currency) } ?: "—"
        listOf(RichBlockTableCell(plainName(nameOf.getValue(memberId), usernames)), RichBlockTableCell(amountText))
    }
    return InputRichMessage(blocks = listOf(RichBlockTable(cells = listOf(header) + rows)))
}

fun splitParticipantKeyboard(
    participantIds: List<MemberId>,
    amountsEntered: Map<MemberId, BigDecimal>,
    nameOf: Map<MemberId, Member>,
    usernames: Map<MemberId, String>,
    currency: String,
): InlineKeyboardMarkup {
    val buttons = participantIds.mapIndexed { index, memberId ->
        val entered = amountsEntered[memberId]
        val name = plainName(nameOf.getValue(memberId), usernames)
        val label = if (entered != null) "$name ✓ ${formatAmount(entered, currency)}" else name
        InlineKeyboardButton(text = label, callbackData = splitPickData(index), disabled = entered != null)
    }
    return InlineKeyboardMarkup(inlineKeyboard = buttons.map { listOf(it) }, forceReply = true)
}

fun splitActionsText(amountsEntered: Map<MemberId, BigDecimal>, amount: BigDecimal, currency: String): String {
    val entered = amountsEntered.values.fold(BigDecimal.ZERO) { acc, v -> acc + v }
    return "Entered ${formatAmount(entered, currency)} of ${formatAmount(amount, currency)}"
}

fun splitActionsKeyboard(canConfirm: Boolean): InlineKeyboardMarkup = InlineKeyboardMarkup(
    inlineKeyboard = listOf(
        listOf(
            InlineKeyboardButton(text = "Cancel", callbackData = SPLIT_CANCEL_DATA),
            InlineKeyboardButton(text = "Confirm", callbackData = SPLIT_CONFIRM_DATA, disabled = !canConfirm),
        ),
    ),
)

fun splitIsReadyToConfirm(participantIds: List<MemberId>, amountsEntered: Map<MemberId, BigDecimal>, amount: BigDecimal): Boolean {
    if (!participantIds.all { it in amountsEntered }) return false
    val entered = amountsEntered.values.fold(BigDecimal.ZERO) { acc, v -> acc + v }
    return entered.compareTo(amount) == 0
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :telegram:test --tests "split.telegram.SplitFlowFormattingSpec"`
Expected: `BUILD SUCCESSFUL`, 9 tests pass.

- [ ] **Step 5: Commit**

```bash
git add telegram/src/main/kotlin/split/telegram/SplitFlowFormatting.kt \
        telegram/src/test/kotlin/split/telegram/SplitFlowFormattingSpec.kt
git commit -m "Add pure message/keyboard builders for the exact-split flow"
```

---

### Task 5: `CommandRouter` — dispatch callback queries and message replies

**Files:**
- Modify: `telegram/src/main/kotlin/split/telegram/CommandRouter.kt`
- Modify: `telegram/src/test/kotlin/split/telegram/CommandRouterSpec.kt`

**Interfaces:**
- Consumes: `TgUpdate.callbackQuery`, `TgMessage.replyToMessage` (Task 1).
- Produces: `data class CallbackContext(chatId: Long, memberId: MemberId, groupId: GroupId, callbackQueryId: String, messageId: Long, data: String)` — `messageId` is the id of the message the tapped button lives on (`callbackQuery.message.messageId`), needed in Task 7 to detect a tap on a superseded flow's stale message even when a *new* flow is active for the same chat. `data class ReplyContext(chatId: Long, memberId: MemberId, groupId: GroupId, replyToMessageId: Long, text: String)`, `typealias CallbackHandler = suspend (CallbackContext) -> Unit`, `typealias ReplyHandler = suspend (ReplyContext) -> Unit`. `CommandRouter`'s constructor gains two new optional trailing parameters: `callbackHandler: CallbackHandler? = null`, `replyHandler: ReplyHandler? = null` — every existing 2-arg call site is unaffected. Task 9 wires the real handlers from Tasks 7–8 into these.

- [ ] **Step 1: Write the failing tests**

Append to `telegram/src/test/kotlin/split/telegram/CommandRouterSpec.kt`, inside the existing `StringSpec({ ... })` block, after the last test:

```kotlin
    "dispatches a callback_query to the registered callback handler" {
        withTestDatabase { db ->
            val callbacks = mutableListOf<CallbackContext>()
            val router = CommandRouter(aResolver(db), emptyMap(), callbackHandler = { callbacks += it })

            router.handleUpdate(
                TgUpdate(
                    updateId = 1,
                    callbackQuery = TgCallbackQuery(
                        id = "cbq1",
                        from = TgUser(id = 1, firstName = "Alice"),
                        message = TgMessage(messageId = 42, chat = TgChat(id = -1, type = "group")),
                        data = "split:mode:equal",
                    ),
                ),
            )

            callbacks.single() shouldBe CallbackContext(
                chatId = -1,
                memberId = callbacks.single().memberId,
                groupId = callbacks.single().groupId,
                callbackQueryId = "cbq1",
                messageId = 42,
                data = "split:mode:equal",
            )
        }
    }

    "does nothing with a callback_query when no callback handler is registered" {
        withTestDatabase { db ->
            val router = CommandRouter(aResolver(db), emptyMap())

            router.handleUpdate(
                TgUpdate(
                    updateId = 1,
                    callbackQuery = TgCallbackQuery(
                        id = "cbq1",
                        from = TgUser(id = 1, firstName = "Alice"),
                        message = TgMessage(messageId = 42, chat = TgChat(id = -1, type = "group")),
                        data = "split:mode:equal",
                    ),
                ),
            )
            // No exception, no crash — that's the whole assertion.
        }
    }

    "ignores a callback_query with no message or no data" {
        withTestDatabase { db ->
            val callbacks = mutableListOf<CallbackContext>()
            val router = CommandRouter(aResolver(db), emptyMap(), callbackHandler = { callbacks += it })

            router.handleUpdate(
                TgUpdate(
                    updateId = 1,
                    callbackQuery = TgCallbackQuery(id = "cbq1", from = TgUser(id = 1, firstName = "Alice"), message = null, data = "x"),
                ),
            )
            router.handleUpdate(
                TgUpdate(
                    updateId = 2,
                    callbackQuery = TgCallbackQuery(
                        id = "cbq2",
                        from = TgUser(id = 1, firstName = "Alice"),
                        message = TgMessage(messageId = 42, chat = TgChat(id = -1, type = "group")),
                        data = null,
                    ),
                ),
            )

            callbacks shouldBe emptyList()
        }
    }

    "dispatches a non-command reply to the registered reply handler" {
        withTestDatabase { db ->
            val replies = mutableListOf<ReplyContext>()
            val router = CommandRouter(aResolver(db), emptyMap(), replyHandler = { replies += it })

            router.handleUpdate(
                TgUpdate(
                    updateId = 1,
                    message = TgMessage(
                        messageId = 7,
                        from = TgUser(id = 1, firstName = "Alice"),
                        chat = TgChat(id = -1, type = "group"),
                        text = "50",
                        replyToMessage = TgMessage(messageId = 3, chat = TgChat(id = -1, type = "group")),
                    ),
                ),
            )

            replies.single() shouldBe ReplyContext(
                chatId = -1,
                memberId = replies.single().memberId,
                groupId = replies.single().groupId,
                replyToMessageId = 3,
                text = "50",
            )
        }
    }

    "does not treat a command as a reply even when it replies to a message" {
        withTestDatabase { db ->
            val replies = mutableListOf<ReplyContext>()
            val helpInvocations = mutableListOf<CommandContext>()
            val router = CommandRouter(
                aResolver(db),
                mapOf("help" to { context: CommandContext -> helpInvocations += context }),
                replyHandler = { replies += it },
            )

            router.handleUpdate(
                TgUpdate(
                    updateId = 1,
                    message = TgMessage(
                        messageId = 7,
                        from = TgUser(id = 1, firstName = "Alice"),
                        chat = TgChat(id = -1, type = "group"),
                        text = "/help",
                        replyToMessage = TgMessage(messageId = 3, chat = TgChat(id = -1, type = "group")),
                    ),
                ),
            )

            helpInvocations.size shouldBe 1
            replies shouldBe emptyList()
        }
    }

    "ignores a plain-text message that isn't a reply, even with a reply handler registered" {
        withTestDatabase { db ->
            val replies = mutableListOf<ReplyContext>()
            val router = CommandRouter(aResolver(db), emptyMap(), replyHandler = { replies += it })

            router.handleUpdate(anUpdate("just chatting"))

            replies shouldBe emptyList()
        }
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :telegram:test --tests "split.telegram.CommandRouterSpec"`
Expected: FAIL — `CallbackContext`, `ReplyContext`, and the `callbackHandler`/`replyHandler` constructor parameters don't exist yet.

- [ ] **Step 3: Rewrite `CommandRouter`**

Replace `telegram/src/main/kotlin/split/telegram/CommandRouter.kt` entirely:

```kotlin
package split.telegram

import split.core.GroupId
import split.core.MemberId

data class CommandContext(
    val chatId: Long,
    val memberId: MemberId,
    val externalUserId: String,
    val groupId: GroupId,
    val args: String,
)

data class CallbackContext(
    val chatId: Long,
    val memberId: MemberId,
    val groupId: GroupId,
    val callbackQueryId: String,
    val messageId: Long,
    val data: String,
)

data class ReplyContext(
    val chatId: Long,
    val memberId: MemberId,
    val groupId: GroupId,
    val replyToMessageId: Long,
    val text: String,
)

typealias CommandHandler = suspend (CommandContext) -> Unit
typealias CallbackHandler = suspend (CallbackContext) -> Unit
typealias ReplyHandler = suspend (ReplyContext) -> Unit

class CommandRouter(
    private val identityResolver: IdentityResolver,
    private val handlers: Map<String, CommandHandler>,
    private val callbackHandler: CallbackHandler? = null,
    private val replyHandler: ReplyHandler? = null,
) {
    suspend fun handleUpdate(update: TgUpdate) {
        val callbackQuery = update.callbackQuery
        if (callbackQuery != null) {
            handleCallbackQuery(callbackQuery)
            return
        }

        val message = update.message ?: return
        val from = message.from ?: return
        val text = message.text ?: return

        if (text.startsWith("/")) {
            handleCommand(message, from, text)
            return
        }

        handleReply(message, from, text)
    }

    private suspend fun handleCallbackQuery(callbackQuery: TgCallbackQuery) {
        val handler = callbackHandler ?: return
        val message = callbackQuery.message ?: return
        val data = callbackQuery.data ?: return

        val memberId = identityResolver.resolveMember(callbackQuery.from.id.toString(), callbackQuery.from.username, callbackQuery.from.firstName)
        val groupId = identityResolver.resolveGroup(message.chat.id.toString())
        identityResolver.ensureGroupMembership(groupId, memberId)

        handler(CallbackContext(message.chat.id, memberId, groupId, callbackQuery.id, message.messageId, data))
    }

    private suspend fun handleCommand(message: TgMessage, from: TgUser, text: String) {
        val (command, args) = parseCommand(text)
        val handler = handlers[command] ?: return

        val memberId = identityResolver.resolveMember(from.id.toString(), from.username, from.firstName)
        val groupId = identityResolver.resolveGroup(message.chat.id.toString())
        identityResolver.ensureGroupMembership(groupId, memberId)

        handler(CommandContext(message.chat.id, memberId, from.id.toString(), groupId, args))
    }

    private suspend fun handleReply(message: TgMessage, from: TgUser, text: String) {
        val handler = replyHandler ?: return
        val replyToId = message.replyToMessage?.messageId ?: return

        val memberId = identityResolver.resolveMember(from.id.toString(), from.username, from.firstName)
        val groupId = identityResolver.resolveGroup(message.chat.id.toString())
        identityResolver.ensureGroupMembership(groupId, memberId)

        handler(ReplyContext(message.chat.id, memberId, groupId, replyToId, text))
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :telegram:test --tests "split.telegram.CommandRouterSpec"`
Expected: `BUILD SUCCESSFUL`, all tests (6 existing + 6 new) pass.

Run: `./gradlew :telegram:test`
Expected: `BUILD SUCCESSFUL` — `PollLoopSpec` and every command spec still pass, since they all construct `CommandRouter` with just the first two arguments.

- [ ] **Step 5: Commit**

```bash
git add telegram/src/main/kotlin/split/telegram/CommandRouter.kt \
        telegram/src/test/kotlin/split/telegram/CommandRouterSpec.kt
git commit -m "Route callback queries and message replies through CommandRouter"
```

---

### Task 6: `/split` starts the mode-choice flow instead of creating an expense directly

**Files:**
- Modify: `telegram/src/main/kotlin/split/telegram/SplitExpenseCommand.kt`
- Modify: `telegram/src/test/kotlin/split/telegram/SplitExpenseCommandSpec.kt`

**Interfaces:**
- Consumes: `SplitFlowStore`, `PendingSplit`, `SplitFlowStage` (Task 3); `SPLIT_MODE_PROMPT`, `splitModeKeyboard` (Task 4); `TelegramApi.sendMessage(..., keyboard)` (Task 2).
- Produces: `SplitExpenseCommand`'s constructor changes from `(platformDirectory, groupRepository, memberRepository, expenseRepository, identityResolver, telegramApi, idGenerator = ..., clock = ...)` to `(platformDirectory, groupRepository, identityResolver, telegramApi, flowStore)` — `memberRepository`/`expenseRepository`/`idGenerator`/`clock` all drop out, since this class no longer creates an `Expense` itself (that, and the id/clock it needs, moves to Task 7's `SplitFlowCallbackHandler`); `flowStore: SplitFlowStore` is added. Task 9 updates its construction site in `BotApplication.kt`.

- [ ] **Step 1: Write the failing tests**

Replace `telegram/src/test/kotlin/split/telegram/SplitExpenseCommandSpec.kt` entirely:

```kotlin
package split.telegram

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import split.storage.ExposedExpenseRepository
import split.storage.ExposedGroupRepository
import split.storage.ExposedMemberRepository
import split.storage.ExposedPlatformDirectory

class SplitExpenseCommandSpec : StringSpec({

    "starts a split-mode choice flow for the sender and mentioned members" {
        withTestDatabase { db ->
            val platformDirectory = ExposedPlatformDirectory(db)
            val groupRepository = ExposedGroupRepository(db)
            val memberRepository = ExposedMemberRepository(db)
            val expenseRepository = ExposedExpenseRepository(db)
            val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)

            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val groupId = resolver.resolveGroup("-100001")
            resolver.ensureGroupMembership(groupId, aliceId)
            val bobbyId = resolver.resolveMember("2", "bobby", "Bob") // Bob has run a command before, so @bobby resolves

            val telegramApi = FakeTelegramApi()
            val flowStore = SplitFlowStore()
            val command = SplitExpenseCommand(platformDirectory, groupRepository, resolver, telegramApi, flowStore)

            command.handle(CommandContext(-100, aliceId, "1", groupId, "90 dinner @bobby"))

            expenseRepository.listActive(groupId) shouldBe emptyList()
            telegramApi.sentMessages.single() shouldBe (-100L to SPLIT_MODE_PROMPT)
            telegramApi.sentKeyboards.single() shouldBe splitModeKeyboard()

            val flow = flowStore.get(-100)
            flow?.invokerId shouldBe aliceId
            flow?.groupId shouldBe groupId
            flow?.amount shouldBe BigDecimal("90.00")
            flow?.currency shouldBe "USD"
            flow?.description shouldBe "dinner"
            flow?.participantIds shouldBe listOf(aliceId, bobbyId)
            flow?.stage shouldBe SplitFlowStage.CHOOSING_MODE
            flow?.promptMessageId shouldBe 1L
        }
    }

    "replies with an error and doesn't start a flow for an unrecognized mention" {
        withTestDatabase { db ->
            val platformDirectory = ExposedPlatformDirectory(db)
            val groupRepository = ExposedGroupRepository(db)
            val memberRepository = ExposedMemberRepository(db)
            val expenseRepository = ExposedExpenseRepository(db)
            val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)

            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val groupId = resolver.resolveGroup("-100001")

            val telegramApi = FakeTelegramApi()
            val flowStore = SplitFlowStore()
            val command = SplitExpenseCommand(platformDirectory, groupRepository, resolver, telegramApi, flowStore)

            command.handle(CommandContext(-100, aliceId, "1", groupId, "90 dinner @stranger"))

            expenseRepository.listActive(groupId) shouldBe emptyList()
            flowStore.get(-100) shouldBe null
            telegramApi.sentMessages.single().second shouldBe
                "I don't recognize @stranger yet — ask them to run /start with me first."
        }
    }
})
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :telegram:test --tests "split.telegram.SplitExpenseCommandSpec"`
Expected: FAIL — `SplitExpenseCommand`'s constructor doesn't take a `flowStore` argument yet, and it still creates an expense immediately.

- [ ] **Step 3: Rewrite `SplitExpenseCommand`**

Replace `telegram/src/main/kotlin/split/telegram/SplitExpenseCommand.kt` entirely:

```kotlin
package split.telegram

import split.core.GroupRepository
import split.core.PlatformDirectory

class SplitExpenseCommand(
    private val platformDirectory: PlatformDirectory,
    private val groupRepository: GroupRepository,
    private val identityResolver: IdentityResolver,
    private val telegramApi: TelegramApi,
    private val flowStore: SplitFlowStore,
) {
    suspend fun handle(context: CommandContext) {
        val group = groupRepository.find(context.groupId) ?: error("Group ${context.groupId} not found")

        val parsed = try {
            parseSplitArgs(context.args, group.defaultCurrency)
        } catch (e: IllegalArgumentException) {
            telegramApi.sendMessage(context.chatId, e.message ?: "Invalid /split usage")
            return
        }

        val participantIds = mutableListOf(context.memberId)
        for (username in parsed.mentionUsernames) {
            val participantId = platformDirectory.findMemberByUsername(IdentityResolver.PLATFORM, username)
            if (participantId == null) {
                telegramApi.sendMessage(
                    context.chatId,
                    "I don't recognize @$username yet — ask them to run /start with me first.",
                )
                return
            }
            participantIds += participantId
        }

        val uniqueParticipantIds = participantIds.distinct()
        for (participantId in uniqueParticipantIds) {
            identityResolver.ensureGroupMembership(context.groupId, participantId)
        }

        val promptMessageId = telegramApi.sendMessage(context.chatId, SPLIT_MODE_PROMPT, splitModeKeyboard())
        flowStore.set(
            context.chatId,
            PendingSplit(
                invokerId = context.memberId,
                groupId = context.groupId,
                amount = parsed.amount,
                currency = parsed.currency,
                description = parsed.description,
                participantIds = uniqueParticipantIds,
                promptMessageId = promptMessageId,
                stage = SplitFlowStage.CHOOSING_MODE,
            ),
        )
    }
}
```

Note: `memberRepository`/`expenseRepository` are dropped from the constructor entirely — this class no longer creates an expense, only starts the flow, so it has no use for them. Task 7's `SplitFlowCallbackHandler` (which does the actual expense creation) takes its own copies of these repositories instead.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :telegram:test --tests "split.telegram.SplitExpenseCommandSpec"`
Expected: `BUILD SUCCESSFUL`, 2 tests pass.

- [ ] **Step 5: Commit**

```bash
git add telegram/src/main/kotlin/split/telegram/SplitExpenseCommand.kt \
        telegram/src/test/kotlin/split/telegram/SplitExpenseCommandSpec.kt
git commit -m "/split starts a split-mode choice flow instead of creating an expense directly"
```

---

### Task 7: `SplitFlowCallbackHandler` — mode choice, participant pick, cancel, confirm

**Files:**
- Create: `telegram/src/main/kotlin/split/telegram/SplitFlowCallbackHandler.kt`
- Test: `telegram/src/test/kotlin/split/telegram/SplitFlowCallbackHandlerSpec.kt`

**Interfaces:**
- Consumes: `SplitFlowStore`, `PendingSplit`, `SplitFlowStage` (Task 3); every `splitXxx` builder (Task 4); `CallbackContext` (Task 5); `resolveEqualSplit`, `resolveExactSplit` (`core`, pre-existing); `formatExpenseConfirmation` (`MessageFormatting.kt`, pre-existing).
- Produces: `class SplitFlowCallbackHandler(flowStore: SplitFlowStore, groupRepository: GroupRepository, memberRepository: MemberRepository, expenseRepository: ExpenseRepository, platformDirectory: PlatformDirectory, telegramApi: TelegramApi, idGenerator: () -> String = { UUID.randomUUID().toString() }, clock: Clock = Clock.systemUTC()) { suspend fun handle(context: CallbackContext) }`. Task 9 wires this into `CommandRouter`'s `callbackHandler`.

- [ ] **Step 1: Write the failing tests**

Create `telegram/src/test/kotlin/split/telegram/SplitFlowCallbackHandlerSpec.kt`:

```kotlin
package split.telegram

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import split.storage.ExposedExpenseRepository
import split.storage.ExposedGroupRepository
import split.storage.ExposedMemberRepository
import split.storage.ExposedPlatformDirectory

class SplitFlowCallbackHandlerSpec : StringSpec({

    "choosing Equal creates an equal-split expense and clears the flow" {
        withTestDatabase { db ->
            val platformDirectory = ExposedPlatformDirectory(db)
            val groupRepository = ExposedGroupRepository(db)
            val memberRepository = ExposedMemberRepository(db)
            val expenseRepository = ExposedExpenseRepository(db)
            val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)

            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val bobId = resolver.resolveMember("2", "bob", "Bob")
            val groupId = resolver.resolveGroup("-100")
            resolver.ensureGroupMembership(groupId, aliceId)
            resolver.ensureGroupMembership(groupId, bobId)

            val telegramApi = FakeTelegramApi()
            val flowStore = SplitFlowStore()
            flowStore.set(
                -100,
                PendingSplit(
                    invokerId = aliceId, groupId = groupId, amount = BigDecimal("90.00"), currency = "USD",
                    description = "dinner", participantIds = listOf(aliceId, bobId), promptMessageId = 1,
                    stage = SplitFlowStage.CHOOSING_MODE,
                ),
            )
            val handler = SplitFlowCallbackHandler(flowStore, groupRepository, memberRepository, expenseRepository, platformDirectory, telegramApi)

            handler.handle(CallbackContext(-100, aliceId, groupId, "cbq1", messageId = 1, SPLIT_MODE_EQUAL_DATA))

            val expense = expenseRepository.listActive(groupId).single()
            expense.shares.associate { it.memberId to it.shareAmount } shouldBe mapOf(
                aliceId to BigDecimal("45.00"),
                bobId to BigDecimal("45.00"),
            )
            telegramApi.editedMessages.single().let { (chatId, messageId, _) -> chatId shouldBe -100L; messageId shouldBe 1L }
            flowStore.get(-100) shouldBe null
        }
    }

    "choosing Exact switches the table message and sends an actions message" {
        withTestDatabase { db ->
            val platformDirectory = ExposedPlatformDirectory(db)
            val groupRepository = ExposedGroupRepository(db)
            val memberRepository = ExposedMemberRepository(db)
            val expenseRepository = ExposedExpenseRepository(db)
            val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)

            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val bobId = resolver.resolveMember("2", "bob", "Bob")
            val groupId = resolver.resolveGroup("-100")
            resolver.ensureGroupMembership(groupId, aliceId)
            resolver.ensureGroupMembership(groupId, bobId)

            val telegramApi = FakeTelegramApi()
            val flowStore = SplitFlowStore()
            flowStore.set(
                -100,
                PendingSplit(
                    invokerId = aliceId, groupId = groupId, amount = BigDecimal("90.00"), currency = "USD",
                    description = "dinner", participantIds = listOf(aliceId, bobId), promptMessageId = 1,
                    stage = SplitFlowStage.CHOOSING_MODE,
                ),
            )
            val handler = SplitFlowCallbackHandler(flowStore, groupRepository, memberRepository, expenseRepository, platformDirectory, telegramApi)

            handler.handle(CallbackContext(-100, aliceId, groupId, "cbq1", messageId = 1, SPLIT_MODE_EXACT_DATA))

            expenseRepository.listActive(groupId) shouldBe emptyList()
            telegramApi.editedRichMessages.single().let { (chatId, messageId, _) -> chatId shouldBe -100L; messageId shouldBe 1L }
            telegramApi.sentMessages.single().first shouldBe -100L

            val flow = flowStore.get(-100)
            flow?.stage shouldBe SplitFlowStage.ENTERING_AMOUNTS
            // This test seeds the flow directly (no prior sendMessage call on this fresh
            // FakeTelegramApi), so the actions message is the *first* message the fake
            // hands out — id 1, not 2.
            flow?.actionsMessageId shouldBe 1L
            flow?.amountsEntered shouldBe emptyMap()
        }
    }

    "picking a participant records them as pending on the flow" {
        withTestDatabase { db ->
            val platformDirectory = ExposedPlatformDirectory(db)
            val groupRepository = ExposedGroupRepository(db)
            val memberRepository = ExposedMemberRepository(db)
            val expenseRepository = ExposedExpenseRepository(db)
            val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)

            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val bobId = resolver.resolveMember("2", "bob", "Bob")
            val groupId = resolver.resolveGroup("-100")

            val telegramApi = FakeTelegramApi()
            val flowStore = SplitFlowStore()
            flowStore.set(
                -100,
                PendingSplit(
                    invokerId = aliceId, groupId = groupId, amount = BigDecimal("90.00"), currency = "USD",
                    description = "dinner", participantIds = listOf(aliceId, bobId), promptMessageId = 1,
                    stage = SplitFlowStage.ENTERING_AMOUNTS, actionsMessageId = 2,
                ),
            )
            val handler = SplitFlowCallbackHandler(flowStore, groupRepository, memberRepository, expenseRepository, platformDirectory, telegramApi)

            handler.handle(CallbackContext(-100, aliceId, groupId, "cbq1", messageId = 1, splitPickData(1)))

            flowStore.get(-100)?.pendingParticipantId shouldBe bobId
        }
    }

    "confirm creates the exact-split expense once amounts are entered" {
        withTestDatabase { db ->
            val platformDirectory = ExposedPlatformDirectory(db)
            val groupRepository = ExposedGroupRepository(db)
            val memberRepository = ExposedMemberRepository(db)
            val expenseRepository = ExposedExpenseRepository(db)
            val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)

            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val bobId = resolver.resolveMember("2", "bob", "Bob")
            val groupId = resolver.resolveGroup("-100")
            resolver.ensureGroupMembership(groupId, aliceId)
            resolver.ensureGroupMembership(groupId, bobId)

            val telegramApi = FakeTelegramApi()
            val flowStore = SplitFlowStore()
            flowStore.set(
                -100,
                PendingSplit(
                    invokerId = aliceId, groupId = groupId, amount = BigDecimal("90.00"), currency = "USD",
                    description = "dinner", participantIds = listOf(aliceId, bobId), promptMessageId = 1,
                    stage = SplitFlowStage.ENTERING_AMOUNTS, actionsMessageId = 2,
                    amountsEntered = mapOf(aliceId to BigDecimal("50.00"), bobId to BigDecimal("40.00")),
                ),
            )
            val handler = SplitFlowCallbackHandler(flowStore, groupRepository, memberRepository, expenseRepository, platformDirectory, telegramApi)

            handler.handle(CallbackContext(-100, aliceId, groupId, "cbq1", messageId = 2, SPLIT_CONFIRM_DATA))

            val expense = expenseRepository.listActive(groupId).single()
            expense.shares.associate { it.memberId to it.shareAmount } shouldBe mapOf(
                aliceId to BigDecimal("50.00"),
                bobId to BigDecimal("40.00"),
            )
            flowStore.get(-100) shouldBe null
        }
    }

    "cancel clears the flow without creating an expense" {
        withTestDatabase { db ->
            val platformDirectory = ExposedPlatformDirectory(db)
            val groupRepository = ExposedGroupRepository(db)
            val memberRepository = ExposedMemberRepository(db)
            val expenseRepository = ExposedExpenseRepository(db)
            val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)

            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val groupId = resolver.resolveGroup("-100")

            val telegramApi = FakeTelegramApi()
            val flowStore = SplitFlowStore()
            flowStore.set(
                -100,
                PendingSplit(
                    invokerId = aliceId, groupId = groupId, amount = BigDecimal("90.00"), currency = "USD",
                    description = "dinner", participantIds = listOf(aliceId), promptMessageId = 1,
                    stage = SplitFlowStage.ENTERING_AMOUNTS, actionsMessageId = 2,
                ),
            )
            val handler = SplitFlowCallbackHandler(flowStore, groupRepository, memberRepository, expenseRepository, platformDirectory, telegramApi)

            handler.handle(CallbackContext(-100, aliceId, groupId, "cbq1", messageId = 2, SPLIT_CANCEL_DATA))

            expenseRepository.listActive(groupId) shouldBe emptyList()
            flowStore.get(-100) shouldBe null
        }
    }

    "a callback from someone other than the invoker is rejected" {
        withTestDatabase { db ->
            val platformDirectory = ExposedPlatformDirectory(db)
            val groupRepository = ExposedGroupRepository(db)
            val memberRepository = ExposedMemberRepository(db)
            val expenseRepository = ExposedExpenseRepository(db)
            val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)

            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val bobId = resolver.resolveMember("2", "bob", "Bob")
            val groupId = resolver.resolveGroup("-100")

            val telegramApi = FakeTelegramApi()
            val flowStore = SplitFlowStore()
            flowStore.set(
                -100,
                PendingSplit(
                    invokerId = aliceId, groupId = groupId, amount = BigDecimal("90.00"), currency = "USD",
                    description = "dinner", participantIds = listOf(aliceId, bobId), promptMessageId = 1,
                    stage = SplitFlowStage.CHOOSING_MODE,
                ),
            )
            val handler = SplitFlowCallbackHandler(flowStore, groupRepository, memberRepository, expenseRepository, platformDirectory, telegramApi)

            handler.handle(CallbackContext(-100, bobId, groupId, "cbq1", messageId = 1, SPLIT_MODE_EQUAL_DATA))

            expenseRepository.listActive(groupId) shouldBe emptyList()
            flowStore.get(-100)?.stage shouldBe SplitFlowStage.CHOOSING_MODE
            telegramApi.answeredCallbacks.single().third shouldBe true // showAlert
        }
    }

    "a callback against a stale, superseded flow's message is rejected" {
        withTestDatabase { db ->
            val platformDirectory = ExposedPlatformDirectory(db)
            val groupRepository = ExposedGroupRepository(db)
            val memberRepository = ExposedMemberRepository(db)
            val expenseRepository = ExposedExpenseRepository(db)
            val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)

            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val groupId = resolver.resolveGroup("-100")

            val telegramApi = FakeTelegramApi()
            val flowStore = SplitFlowStore()
            // A new /split has replaced the flow whose mode-choice message was id 1 — the
            // current flow's prompt now lives on message id 5. A tap on the old message 1
            // (still visible in the chat, just no longer "the" flow) must not be honored.
            flowStore.set(
                -100,
                PendingSplit(
                    invokerId = aliceId, groupId = groupId, amount = BigDecimal("30.00"), currency = "USD",
                    description = "coffee", participantIds = listOf(aliceId), promptMessageId = 5,
                    stage = SplitFlowStage.CHOOSING_MODE,
                ),
            )
            val handler = SplitFlowCallbackHandler(flowStore, groupRepository, memberRepository, expenseRepository, platformDirectory, telegramApi)

            handler.handle(CallbackContext(-100, aliceId, groupId, "cbq1", messageId = 1, SPLIT_MODE_EQUAL_DATA))

            expenseRepository.listActive(groupId) shouldBe emptyList()
            flowStore.get(-100)?.promptMessageId shouldBe 5 // the current flow is untouched
            telegramApi.answeredCallbacks.single().third shouldBe true // showAlert
        }
    }

    "a callback for a chat with no pending flow is answered but ignored" {
        withTestDatabase { db ->
            val platformDirectory = ExposedPlatformDirectory(db)
            val groupRepository = ExposedGroupRepository(db)
            val memberRepository = ExposedMemberRepository(db)
            val expenseRepository = ExposedExpenseRepository(db)
            val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)
            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val groupId = resolver.resolveGroup("-100")

            val telegramApi = FakeTelegramApi()
            val flowStore = SplitFlowStore()
            val handler = SplitFlowCallbackHandler(flowStore, groupRepository, memberRepository, expenseRepository, platformDirectory, telegramApi)

            handler.handle(CallbackContext(-100, aliceId, groupId, "cbq1", messageId = 1, SPLIT_MODE_EQUAL_DATA))

            telegramApi.answeredCallbacks.single().first shouldBe "cbq1"
            expenseRepository.listActive(groupId) shouldBe emptyList()
        }
    }
})
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :telegram:test --tests "split.telegram.SplitFlowCallbackHandlerSpec"`
Expected: FAIL — `SplitFlowCallbackHandler` doesn't exist yet.

- [ ] **Step 3: Write the implementation**

Create `telegram/src/main/kotlin/split/telegram/SplitFlowCallbackHandler.kt`:

```kotlin
package split.telegram

import split.core.Expense
import split.core.ExpenseId
import split.core.ExpenseRepository
import split.core.GroupRepository
import split.core.MemberRepository
import split.core.PlatformDirectory
import split.core.SplitType
import split.core.resolveEqualSplit
import split.core.resolveExactSplit
import java.time.Clock
import java.time.Instant
import java.util.UUID

class SplitFlowCallbackHandler(
    private val flowStore: SplitFlowStore,
    private val groupRepository: GroupRepository,
    private val memberRepository: MemberRepository,
    private val expenseRepository: ExpenseRepository,
    private val platformDirectory: PlatformDirectory,
    private val telegramApi: TelegramApi,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun handle(context: CallbackContext) {
        val flow = flowStore.get(context.chatId)
        if (flow == null) {
            telegramApi.answerCallbackQuery(context.callbackQueryId, "This split is no longer active.", showAlert = true)
            return
        }
        // A newer /split may have replaced this chat's flow since this button was shown —
        // the store only keeps the current flow, so without this check a tap on a stale
        // message would be silently processed against the wrong (newer) flow's data.
        if (context.messageId != flow.promptMessageId && context.messageId != flow.actionsMessageId) {
            telegramApi.answerCallbackQuery(context.callbackQueryId, "This split is no longer active.", showAlert = true)
            return
        }
        if (context.memberId != flow.invokerId) {
            telegramApi.answerCallbackQuery(context.callbackQueryId, "Only the person who started this split can do that.", showAlert = true)
            return
        }

        when {
            context.data == SPLIT_MODE_EQUAL_DATA && flow.stage == SplitFlowStage.CHOOSING_MODE -> chooseEqual(context, flow)
            context.data == SPLIT_MODE_EXACT_DATA && flow.stage == SplitFlowStage.CHOOSING_MODE -> chooseExact(context, flow)
            context.data.startsWith(SPLIT_PICK_PREFIX) && flow.stage == SplitFlowStage.ENTERING_AMOUNTS -> pickParticipant(context, flow)
            context.data == SPLIT_CANCEL_DATA && flow.stage == SplitFlowStage.ENTERING_AMOUNTS -> cancel(context, flow)
            context.data == SPLIT_CONFIRM_DATA && flow.stage == SplitFlowStage.ENTERING_AMOUNTS -> confirm(context, flow)
            else -> telegramApi.answerCallbackQuery(context.callbackQueryId, "This split is no longer active.", showAlert = true)
        }
    }

    private suspend fun chooseEqual(context: CallbackContext, flow: PendingSplit) {
        val (members, usernames) = membersAndUsernames(flow)
        val shares = resolveEqualSplit(flow.amount, flow.invokerId, flow.participantIds)
        val expense = createExpense(flow, SplitType.EQUAL, shares)

        telegramApi.editMessageText(context.chatId, flow.promptMessageId, formatExpenseConfirmation(expense, members, usernames))
        flowStore.clear(context.chatId)
        telegramApi.answerCallbackQuery(context.callbackQueryId)
    }

    private suspend fun chooseExact(context: CallbackContext, flow: PendingSplit) {
        val (members, usernames) = membersAndUsernames(flow)
        val nameOf = members.associateBy { it.id }

        telegramApi.editRichMessage(
            context.chatId,
            flow.promptMessageId,
            splitAmountsTable(flow.participantIds, emptyMap(), nameOf, usernames, flow.currency),
            splitParticipantKeyboard(flow.participantIds, emptyMap(), nameOf, usernames, flow.currency),
        )
        val actionsMessageId = telegramApi.sendMessage(
            context.chatId,
            splitActionsText(emptyMap(), flow.amount, flow.currency),
            splitActionsKeyboard(canConfirm = false),
        )
        flowStore.set(context.chatId, flow.copy(stage = SplitFlowStage.ENTERING_AMOUNTS, actionsMessageId = actionsMessageId))
        telegramApi.answerCallbackQuery(context.callbackQueryId)
    }

    private suspend fun pickParticipant(context: CallbackContext, flow: PendingSplit) {
        val index = context.data.removePrefix(SPLIT_PICK_PREFIX).toIntOrNull()
        val memberId = index?.let { flow.participantIds.getOrNull(it) }
        if (memberId == null) {
            telegramApi.answerCallbackQuery(context.callbackQueryId, "This split is no longer active.", showAlert = true)
            return
        }
        flowStore.set(context.chatId, flow.copy(pendingParticipantId = memberId))
        telegramApi.answerCallbackQuery(context.callbackQueryId)
    }

    private suspend fun cancel(context: CallbackContext, flow: PendingSplit) {
        telegramApi.editMessageText(context.chatId, flow.promptMessageId, "Split cancelled.")
        flow.actionsMessageId?.let { telegramApi.editMessageText(context.chatId, it, "Split cancelled.") }
        flowStore.clear(context.chatId)
        telegramApi.answerCallbackQuery(context.callbackQueryId)
    }

    private suspend fun confirm(context: CallbackContext, flow: PendingSplit) {
        val shares = try {
            resolveExactSplit(flow.amount, flow.amountsEntered)
        } catch (e: IllegalArgumentException) {
            telegramApi.answerCallbackQuery(context.callbackQueryId, e.message ?: "Amounts don't add up yet.", showAlert = true)
            return
        }
        val (members, usernames) = membersAndUsernames(flow)
        val expense = createExpense(flow, SplitType.EXACT, shares)

        telegramApi.editMessageText(context.chatId, flow.promptMessageId, formatExpenseConfirmation(expense, members, usernames))
        flow.actionsMessageId?.let { telegramApi.editMessageText(context.chatId, it, "Done.") }
        flowStore.clear(context.chatId)
        telegramApi.answerCallbackQuery(context.callbackQueryId)
    }

    private suspend fun membersAndUsernames(flow: PendingSplit): Pair<List<split.core.Member>, Map<split.core.MemberId, String>> {
        val members = memberRepository.findByGroup(flow.groupId)
        val usernames = platformDirectory.findUsernames(IdentityResolver.PLATFORM, members.map { it.id })
        return members to usernames
    }

    private suspend fun createExpense(flow: PendingSplit, splitType: SplitType, shares: List<split.core.ExpenseShare>): Expense {
        val expense = Expense(
            id = ExpenseId(idGenerator()),
            groupId = flow.groupId,
            currency = flow.currency,
            description = flow.description,
            amount = flow.amount,
            payerId = flow.invokerId,
            splitType = splitType,
            createdBy = flow.invokerId,
            createdAt = Instant.now(clock),
            shares = shares,
        )
        expenseRepository.create(expense)
        return expense
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :telegram:test --tests "split.telegram.SplitFlowCallbackHandlerSpec"`
Expected: `BUILD SUCCESSFUL`, all 9 tests pass.

- [ ] **Step 5: Commit**

```bash
git add telegram/src/main/kotlin/split/telegram/SplitFlowCallbackHandler.kt \
        telegram/src/test/kotlin/split/telegram/SplitFlowCallbackHandlerSpec.kt
git commit -m "Add SplitFlowCallbackHandler for mode choice, picking, cancel, and confirm"
```

---

### Task 8: `SplitFlowReplyHandler` — amount entry, plus a full end-to-end flow test

**Files:**
- Create: `telegram/src/main/kotlin/split/telegram/SplitFlowReplyHandler.kt`
- Test: `telegram/src/test/kotlin/split/telegram/SplitFlowReplyHandlerSpec.kt`
- Test: `telegram/src/test/kotlin/split/telegram/SplitFlowSpec.kt`

**Interfaces:**
- Consumes: `SplitFlowStore`, `PendingSplit`, `SplitFlowStage` (Task 3); `splitAmountsTable`, `splitParticipantKeyboard`, `splitActionsText`, `splitActionsKeyboard`, `splitIsReadyToConfirm` (Task 4); `ReplyContext` (Task 5).
- Produces: `class SplitFlowReplyHandler(flowStore: SplitFlowStore, memberRepository: MemberRepository, platformDirectory: PlatformDirectory, telegramApi: TelegramApi) { suspend fun handle(context: ReplyContext) }`. Task 9 wires this into `CommandRouter`'s `replyHandler`.

- [ ] **Step 1: Write the failing tests**

Create `telegram/src/test/kotlin/split/telegram/SplitFlowReplyHandlerSpec.kt`:

```kotlin
package split.telegram

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import split.storage.ExposedGroupRepository
import split.storage.ExposedMemberRepository
import split.storage.ExposedPlatformDirectory

class SplitFlowReplyHandlerSpec : StringSpec({

    "a valid amount reply fills in the pending participant and edits both messages" {
        withTestDatabase { db ->
            val platformDirectory = ExposedPlatformDirectory(db)
            val memberRepository = ExposedMemberRepository(db)
            val resolver = IdentityResolver(platformDirectory, memberRepository, ExposedGroupRepository(db))

            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val bobId = resolver.resolveMember("2", "bob", "Bob")
            val groupId = resolver.resolveGroup("-100")
            resolver.ensureGroupMembership(groupId, aliceId)
            resolver.ensureGroupMembership(groupId, bobId)

            val telegramApi = FakeTelegramApi()
            val flowStore = SplitFlowStore()
            flowStore.set(
                -100,
                PendingSplit(
                    invokerId = aliceId, groupId = groupId, amount = BigDecimal("90.00"), currency = "USD",
                    description = "dinner", participantIds = listOf(aliceId, bobId), promptMessageId = 1,
                    stage = SplitFlowStage.ENTERING_AMOUNTS, actionsMessageId = 2, pendingParticipantId = bobId,
                ),
            )
            val handler = SplitFlowReplyHandler(flowStore, memberRepository, platformDirectory, telegramApi)

            handler.handle(ReplyContext(-100, aliceId, groupId, replyToMessageId = 1, text = "40"))

            val flow = flowStore.get(-100)
            flow?.amountsEntered shouldBe mapOf(bobId to BigDecimal("40"))
            flow?.pendingParticipantId shouldBe null
            telegramApi.editedRichMessages.single().let { (chatId, messageId, _) -> chatId shouldBe -100L; messageId shouldBe 1L }
            telegramApi.editedMessages.single().let { (chatId, messageId, _) -> chatId shouldBe -100L; messageId shouldBe 2L }
        }
    }

    "an invalid amount reply doesn't touch state and asks again" {
        withTestDatabase { db ->
            val platformDirectory = ExposedPlatformDirectory(db)
            val memberRepository = ExposedMemberRepository(db)
            val resolver = IdentityResolver(platformDirectory, memberRepository, ExposedGroupRepository(db))
            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val groupId = resolver.resolveGroup("-100")

            val telegramApi = FakeTelegramApi()
            val flowStore = SplitFlowStore()
            val pending = PendingSplit(
                invokerId = aliceId, groupId = groupId, amount = BigDecimal("90.00"), currency = "USD",
                description = "dinner", participantIds = listOf(aliceId), promptMessageId = 1,
                stage = SplitFlowStage.ENTERING_AMOUNTS, actionsMessageId = 2, pendingParticipantId = aliceId,
            )
            flowStore.set(-100, pending)
            val handler = SplitFlowReplyHandler(flowStore, memberRepository, platformDirectory, telegramApi)

            handler.handle(ReplyContext(-100, aliceId, groupId, replyToMessageId = 1, text = "not a number"))

            flowStore.get(-100) shouldBe pending
            telegramApi.sentMessages.single().second shouldBe "That doesn't look like an amount — reply with a number, e.g. 42.50."
        }
    }

    "a reply from someone other than the invoker is ignored" {
        withTestDatabase { db ->
            val platformDirectory = ExposedPlatformDirectory(db)
            val memberRepository = ExposedMemberRepository(db)
            val resolver = IdentityResolver(platformDirectory, memberRepository, ExposedGroupRepository(db))
            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val bobId = resolver.resolveMember("2", "bob", "Bob")
            val groupId = resolver.resolveGroup("-100")

            val telegramApi = FakeTelegramApi()
            val flowStore = SplitFlowStore()
            val pending = PendingSplit(
                invokerId = aliceId, groupId = groupId, amount = BigDecimal("90.00"), currency = "USD",
                description = "dinner", participantIds = listOf(aliceId, bobId), promptMessageId = 1,
                stage = SplitFlowStage.ENTERING_AMOUNTS, actionsMessageId = 2, pendingParticipantId = bobId,
            )
            flowStore.set(-100, pending)
            val handler = SplitFlowReplyHandler(flowStore, memberRepository, platformDirectory, telegramApi)

            handler.handle(ReplyContext(-100, bobId, groupId, replyToMessageId = 1, text = "40"))

            flowStore.get(-100) shouldBe pending
            telegramApi.sentMessages shouldBe emptyList()
        }
    }

    "a reply to the wrong message is ignored" {
        withTestDatabase { db ->
            val platformDirectory = ExposedPlatformDirectory(db)
            val memberRepository = ExposedMemberRepository(db)
            val resolver = IdentityResolver(platformDirectory, memberRepository, ExposedGroupRepository(db))
            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val groupId = resolver.resolveGroup("-100")

            val telegramApi = FakeTelegramApi()
            val flowStore = SplitFlowStore()
            val pending = PendingSplit(
                invokerId = aliceId, groupId = groupId, amount = BigDecimal("90.00"), currency = "USD",
                description = "dinner", participantIds = listOf(aliceId), promptMessageId = 1,
                stage = SplitFlowStage.ENTERING_AMOUNTS, actionsMessageId = 2, pendingParticipantId = aliceId,
            )
            flowStore.set(-100, pending)
            val handler = SplitFlowReplyHandler(flowStore, memberRepository, platformDirectory, telegramApi)

            handler.handle(ReplyContext(-100, aliceId, groupId, replyToMessageId = 999, text = "40"))

            flowStore.get(-100) shouldBe pending
        }
    }

    "a reply when no participant is pending is ignored" {
        withTestDatabase { db ->
            val platformDirectory = ExposedPlatformDirectory(db)
            val memberRepository = ExposedMemberRepository(db)
            val resolver = IdentityResolver(platformDirectory, memberRepository, ExposedGroupRepository(db))
            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val groupId = resolver.resolveGroup("-100")

            val telegramApi = FakeTelegramApi()
            val flowStore = SplitFlowStore()
            val pending = PendingSplit(
                invokerId = aliceId, groupId = groupId, amount = BigDecimal("90.00"), currency = "USD",
                description = "dinner", participantIds = listOf(aliceId), promptMessageId = 1,
                stage = SplitFlowStage.ENTERING_AMOUNTS, actionsMessageId = 2, pendingParticipantId = null,
            )
            flowStore.set(-100, pending)
            val handler = SplitFlowReplyHandler(flowStore, memberRepository, platformDirectory, telegramApi)

            handler.handle(ReplyContext(-100, aliceId, groupId, replyToMessageId = 1, text = "40"))

            flowStore.get(-100) shouldBe pending
        }
    }
})
```

Create `telegram/src/test/kotlin/split/telegram/SplitFlowSpec.kt` — a full end-to-end test chaining `SplitExpenseCommand`, `SplitFlowCallbackHandler`, and `SplitFlowReplyHandler` together, to prove the whole flow (not just each piece in isolation) actually works:

```kotlin
package split.telegram

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import split.storage.ExposedExpenseRepository
import split.storage.ExposedGroupRepository
import split.storage.ExposedMemberRepository
import split.storage.ExposedPlatformDirectory

class SplitFlowSpec : StringSpec({

    "the full exact-split flow: choose Exact, enter both amounts, confirm" {
        withTestDatabase { db ->
            val platformDirectory = ExposedPlatformDirectory(db)
            val groupRepository = ExposedGroupRepository(db)
            val memberRepository = ExposedMemberRepository(db)
            val expenseRepository = ExposedExpenseRepository(db)
            val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)

            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val groupId = resolver.resolveGroup("-100")
            resolver.ensureGroupMembership(groupId, aliceId)
            val bobId = resolver.resolveMember("2", "bob", "Bob")

            val telegramApi = FakeTelegramApi()
            val flowStore = SplitFlowStore()
            val splitCommand = SplitExpenseCommand(platformDirectory, groupRepository, resolver, telegramApi, flowStore)
            val callbackHandler = SplitFlowCallbackHandler(flowStore, groupRepository, memberRepository, expenseRepository, platformDirectory, telegramApi)
            val replyHandler = SplitFlowReplyHandler(flowStore, memberRepository, platformDirectory, telegramApi)

            // /split 90 dinner @bob
            splitCommand.handle(CommandContext(-100, aliceId, "1", groupId, "90 dinner @bob"))
            val promptMessageId = flowStore.get(-100)!!.promptMessageId

            // Tap "Exact" (button lives on the mode-choice message, i.e. promptMessageId)
            callbackHandler.handle(CallbackContext(-100, aliceId, groupId, "cbq1", promptMessageId, SPLIT_MODE_EXACT_DATA))
            val actionsMessageId = flowStore.get(-100)!!.actionsMessageId!!

            // Tap Alice's button (on the table message, still promptMessageId), reply "50"
            callbackHandler.handle(CallbackContext(-100, aliceId, groupId, "cbq2", promptMessageId, splitPickData(0)))
            replyHandler.handle(ReplyContext(-100, aliceId, groupId, promptMessageId, "50"))

            // Not confirmable yet — only one of two participants has an amount
            flowStore.get(-100)?.stage shouldBe SplitFlowStage.ENTERING_AMOUNTS
            expenseRepository.listActive(groupId) shouldBe emptyList()

            // Tap Bob's button, reply "40"
            callbackHandler.handle(CallbackContext(-100, aliceId, groupId, "cbq3", promptMessageId, splitPickData(1)))
            replyHandler.handle(ReplyContext(-100, aliceId, groupId, promptMessageId, "40"))

            // Tap "Confirm" (button lives on the actions message)
            callbackHandler.handle(CallbackContext(-100, aliceId, groupId, "cbq4", actionsMessageId, SPLIT_CONFIRM_DATA))

            val expense = expenseRepository.listActive(groupId).single()
            expense.shares.associate { it.memberId to it.shareAmount } shouldBe mapOf(
                aliceId to BigDecimal("50"),
                bobId to BigDecimal("40"),
            )
            flowStore.get(-100) shouldBe null
        }
    }

    "re-entering a participant's amount before confirming overwrites the earlier value" {
        withTestDatabase { db ->
            val platformDirectory = ExposedPlatformDirectory(db)
            val groupRepository = ExposedGroupRepository(db)
            val memberRepository = ExposedMemberRepository(db)
            val expenseRepository = ExposedExpenseRepository(db)
            val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)

            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val groupId = resolver.resolveGroup("-100")
            resolver.ensureGroupMembership(groupId, aliceId)
            val bobId = resolver.resolveMember("2", "bob", "Bob")

            val telegramApi = FakeTelegramApi()
            val flowStore = SplitFlowStore()
            val splitCommand = SplitExpenseCommand(platformDirectory, groupRepository, resolver, telegramApi, flowStore)
            val callbackHandler = SplitFlowCallbackHandler(flowStore, groupRepository, memberRepository, expenseRepository, platformDirectory, telegramApi)
            val replyHandler = SplitFlowReplyHandler(flowStore, memberRepository, platformDirectory, telegramApi)

            splitCommand.handle(CommandContext(-100, aliceId, "1", groupId, "90 dinner @bob"))
            val promptMessageId = flowStore.get(-100)!!.promptMessageId
            callbackHandler.handle(CallbackContext(-100, aliceId, groupId, "cbq1", promptMessageId, SPLIT_MODE_EXACT_DATA))
            val actionsMessageId = flowStore.get(-100)!!.actionsMessageId!!

            // Enter Alice at 50, then Bob at 40 — total 90, ready to confirm.
            callbackHandler.handle(CallbackContext(-100, aliceId, groupId, "cbq2", promptMessageId, splitPickData(0)))
            replyHandler.handle(ReplyContext(-100, aliceId, groupId, promptMessageId, "50"))
            callbackHandler.handle(CallbackContext(-100, aliceId, groupId, "cbq3", promptMessageId, splitPickData(1)))
            replyHandler.handle(ReplyContext(-100, aliceId, groupId, promptMessageId, "40"))
            splitIsReadyToConfirm(listOf(aliceId, bobId), flowStore.get(-100)!!.amountsEntered, BigDecimal("90.00")) shouldBe true

            // Tap Alice again and change her amount to 60 — now over budget, not confirmable.
            callbackHandler.handle(CallbackContext(-100, aliceId, groupId, "cbq4", promptMessageId, splitPickData(0)))
            replyHandler.handle(ReplyContext(-100, aliceId, groupId, promptMessageId, "60"))

            flowStore.get(-100)?.amountsEntered shouldBe mapOf(aliceId to BigDecimal("60"), bobId to BigDecimal("40"))
            splitIsReadyToConfirm(listOf(aliceId, bobId), flowStore.get(-100)!!.amountsEntered, BigDecimal("90.00")) shouldBe false
        }
    }

    "a second /split replaces the first, still-pending flow" {
        withTestDatabase { db ->
            val platformDirectory = ExposedPlatformDirectory(db)
            val groupRepository = ExposedGroupRepository(db)
            val memberRepository = ExposedMemberRepository(db)
            val expenseRepository = ExposedExpenseRepository(db)
            val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)

            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val groupId = resolver.resolveGroup("-100")
            resolver.ensureGroupMembership(groupId, aliceId)

            val telegramApi = FakeTelegramApi()
            val flowStore = SplitFlowStore()
            val splitCommand = SplitExpenseCommand(platformDirectory, groupRepository, resolver, telegramApi, flowStore)
            val callbackHandler = SplitFlowCallbackHandler(flowStore, groupRepository, memberRepository, expenseRepository, platformDirectory, telegramApi)

            splitCommand.handle(CommandContext(-100, aliceId, "1", groupId, "30 coffee"))
            val firstPromptMessageId = flowStore.get(-100)!!.promptMessageId

            splitCommand.handle(CommandContext(-100, aliceId, "1", groupId, "90 dinner"))
            val secondFlow = flowStore.get(-100)!!
            secondFlow.promptMessageId shouldBe firstPromptMessageId + 1
            secondFlow.description shouldBe "dinner"

            // A tap on the first (now stale) message is rejected, not applied to the new flow.
            callbackHandler.handle(CallbackContext(-100, aliceId, groupId, "cbq1", firstPromptMessageId, SPLIT_MODE_EQUAL_DATA))

            expenseRepository.listActive(groupId) shouldBe emptyList()
            flowStore.get(-100)?.description shouldBe "dinner"
        }
    }
})
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :telegram:test --tests "split.telegram.SplitFlowReplyHandlerSpec" --tests "split.telegram.SplitFlowSpec"`
Expected: FAIL — `SplitFlowReplyHandler` doesn't exist yet.

- [ ] **Step 3: Write the implementation**

Create `telegram/src/main/kotlin/split/telegram/SplitFlowReplyHandler.kt`:

```kotlin
package split.telegram

import split.core.MemberRepository
import split.core.PlatformDirectory

class SplitFlowReplyHandler(
    private val flowStore: SplitFlowStore,
    private val memberRepository: MemberRepository,
    private val platformDirectory: PlatformDirectory,
    private val telegramApi: TelegramApi,
) {
    suspend fun handle(context: ReplyContext) {
        val flow = flowStore.get(context.chatId) ?: return
        if (flow.stage != SplitFlowStage.ENTERING_AMOUNTS) return
        if (context.replyToMessageId != flow.promptMessageId) return
        if (context.memberId != flow.invokerId) return
        val pendingParticipantId = flow.pendingParticipantId ?: return

        val amount = context.text.trim().toBigDecimalOrNull()
        if (amount == null) {
            telegramApi.sendMessage(context.chatId, "That doesn't look like an amount — reply with a number, e.g. 42.50.")
            return
        }

        val updatedAmounts = flow.amountsEntered + (pendingParticipantId to amount)
        val updatedFlow = flow.copy(amountsEntered = updatedAmounts, pendingParticipantId = null)
        flowStore.set(context.chatId, updatedFlow)

        val members = memberRepository.findByGroup(flow.groupId)
        val usernames = platformDirectory.findUsernames(IdentityResolver.PLATFORM, members.map { it.id })
        val nameOf = members.associateBy { it.id }

        telegramApi.editRichMessage(
            context.chatId,
            flow.promptMessageId,
            splitAmountsTable(flow.participantIds, updatedAmounts, nameOf, usernames, flow.currency),
            splitParticipantKeyboard(flow.participantIds, updatedAmounts, nameOf, usernames, flow.currency),
        )
        val canConfirm = splitIsReadyToConfirm(flow.participantIds, updatedAmounts, flow.amount)
        flow.actionsMessageId?.let {
            telegramApi.editMessageText(
                context.chatId,
                it,
                splitActionsText(updatedAmounts, flow.amount, flow.currency),
                splitActionsKeyboard(canConfirm),
            )
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :telegram:test --tests "split.telegram.SplitFlowReplyHandlerSpec" --tests "split.telegram.SplitFlowSpec"`
Expected: `BUILD SUCCESSFUL`, all tests pass (5 + 3).

Run: `./gradlew :telegram:test`
Expected: `BUILD SUCCESSFUL` for the whole module.

- [ ] **Step 5: Commit**

```bash
git add telegram/src/main/kotlin/split/telegram/SplitFlowReplyHandler.kt \
        telegram/src/test/kotlin/split/telegram/SplitFlowReplyHandlerSpec.kt \
        telegram/src/test/kotlin/split/telegram/SplitFlowSpec.kt
git commit -m "Add SplitFlowReplyHandler and an end-to-end exact-split flow test"
```

---

### Task 9: Wire the flow into `BotApplication.kt`

**Files:**
- Modify: `telegram/src/main/kotlin/split/telegram/BotApplication.kt`

**Interfaces:**
- Consumes: `SplitFlowStore` (Task 3), `SplitFlowCallbackHandler` (Task 7), `SplitFlowReplyHandler` (Task 8), `CommandRouter`'s `callbackHandler`/`replyHandler` parameters (Task 5), `SplitExpenseCommand`'s new `flowStore` parameter (Task 6).
- Produces: nothing new — this is the composition root, nothing else depends on it.

- [ ] **Step 1: Update the wiring**

In `telegram/src/main/kotlin/split/telegram/BotApplication.kt`, replace:

```kotlin
    val telegramApi = HttpTelegramApi(botToken)
    val identityResolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)

    val handlers = mapOf(
        "start" to StartCommand(groupRepository, telegramApi)::handle,
        "help" to HelpCommand(telegramApi)::handle,
        "currency" to CurrencyCommand(groupRepository, telegramApi)::handle,
        "members" to MembersCommand(memberRepository, platformDirectory, telegramApi)::handle,
        "split" to SplitExpenseCommand(
            platformDirectory, groupRepository, memberRepository, expenseRepository, identityResolver, telegramApi,
        )::handle,
```

with:

```kotlin
    val telegramApi = HttpTelegramApi(botToken)
    val identityResolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)
    val splitFlowStore = SplitFlowStore()
    val splitFlowCallbackHandler = SplitFlowCallbackHandler(
        splitFlowStore, groupRepository, memberRepository, expenseRepository, platformDirectory, telegramApi,
    )
    val splitFlowReplyHandler = SplitFlowReplyHandler(splitFlowStore, memberRepository, platformDirectory, telegramApi)

    val handlers = mapOf(
        "start" to StartCommand(groupRepository, telegramApi)::handle,
        "help" to HelpCommand(telegramApi)::handle,
        "currency" to CurrencyCommand(groupRepository, telegramApi)::handle,
        "members" to MembersCommand(memberRepository, platformDirectory, telegramApi)::handle,
        "split" to SplitExpenseCommand(platformDirectory, groupRepository, identityResolver, telegramApi, splitFlowStore)::handle,
```

Then replace:

```kotlin
    val router = CommandRouter(identityResolver, handlers)
```

with:

```kotlin
    val router = CommandRouter(
        identityResolver,
        handlers,
        callbackHandler = splitFlowCallbackHandler::handle,
        replyHandler = splitFlowReplyHandler::handle,
    )
```

- [ ] **Step 2: Verify the whole module builds and all tests still pass**

Run: `./gradlew :telegram:test`
Expected: `BUILD SUCCESSFUL`, every test in the module passes — this file has no dedicated spec (matching the existing codebase, where `BotApplication.kt`'s wiring block isn't unit-tested, only `PollLoop` is via `PollLoopSpec`), so this is the verification step for Task 9.

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL` — full project build across `core`, `storage`, and `telegram`.

- [ ] **Step 3: Commit**

```bash
git add telegram/src/main/kotlin/split/telegram/BotApplication.kt
git commit -m "Wire the exact-split flow into BotApplication"
```
