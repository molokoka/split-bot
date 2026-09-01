# Telegram Adapter Module Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the `telegram` Gradle module — the Telegram Bot API adapter (long-polling, command parsing, identity resolution, command handlers) that turns the already-implemented `core` domain and `storage` persistence layers into a working bill-splitting bot.

**Architecture:** A new `telegram` module depends on `core` (domain types + repository ports) and `storage` (Exposed repository implementations). It talks to the Telegram Bot API over plain HTTP (no bot framework), resolves each incoming message's sender/chat to a `MemberId`/`GroupId` via `IdentityResolver`, and dispatches `/command` text through a small `CommandRouter` to one handler class per command. Every command handler is a thin coroutine function: read via repositories, run pure `core` logic (split resolution, balances, debt simplification), write via repositories, reply via `TelegramApi`.

**Tech Stack:** Kotlin 2.4.0 / JVM 17, kotlinx-coroutines-core 1.11.0, kotlinx-serialization-json 1.11.0, Ktor client 3.5.2 (`ktor-client-core`, `ktor-client-cio` engine, `ktor-client-content-negotiation`, `ktor-serialization-kotlinx-json`) for the Telegram Bot API, Kotest 5.9.1 (`StringSpec`), `ktor-client-mock`'s `MockEngine` for `HttpTelegramApi` tests.

**Spec:** `docs/superpowers/specs/2026-08-26-telegram-split-bot-design.md` (bot design — architecture, identity resolution, commands, debt simplification) and `docs/superpowers/specs/2026-08-27-sqlite-persistence-design.md` (already-implemented `core`/`storage` layers this plan builds on).

## Global Constraints

- Kotlin `2.4.0`, JVM toolchain `17` — matches `core`/`storage`.
- Kotest `5.9.1` (`kotest-runner-junit5`, `kotest-assertions-core`), `StringSpec` style, matching existing `core`/`storage` tests.
- `kotlinx-coroutines-core` `1.11.0` — matches `storage`.
- `kotlinx-serialization-json` `1.11.0` (verified current on Maven Central at plan-writing time).
- Ktor client `3.5.2` (`ktor-client-core`, `ktor-client-cio` engine, `ktor-client-content-negotiation`, `ktor-serialization-kotlinx-json`; `ktor-client-mock` for tests — all verified current on Maven Central at plan-writing time) for the Telegram Bot API HTTP calls. Ktor's client is coroutine-native, so unlike `storage`'s JDBC calls, HTTP calls do **not** need `withContext(Dispatchers.IO)` wrapping.
- Ktor code (`HttpTelegramApi.kt` and its test) uses wildcard `io.ktor.*` imports — a deliberate, scoped exception to this project's usual explicit-import convention, matching Ktor's own idiomatic style (its DSL surface, e.g. `install(...)`, is large and always used this way in Ktor's own docs and samples).
- All I/O-touching functions are `suspend fun`; JDBC work in `storage` is wrapped in `withContext(Dispatchers.IO)` (unchanged from Task 1 on).
- Tests prefer real integration doubles over mocks: a real temp-file SQLite database via `connectDatabase` (same as `storage`'s `withTestDatabase`) for anything touching repositories, and Ktor's `MockEngine` for anything touching `HttpTelegramApi`. A `FakeTelegramApi` in-memory recorder is used only for command-handler/router tests that don't need real HTTP.
- Platform identifier string is always the literal `"telegram"` (matches `PlatformIdentity`/`PlatformGroupLink.platform`).
- Money stays `BigDecimal` end-to-end in this module — the adapter never touches cents; `storage` already handles that conversion at its own boundary.
- IDs are generated with `UUID.randomUUID().toString()`, wrapped in the relevant `core` value class (`MemberId`, `GroupId`, `ExpenseId`).

## Out of scope for this plan

- Inline-keyboard flows: the button-based participant picker for `/add` with no mentions, "+ Add someone new" button, and the "Change split" (Exact/Shares) follow-up — `/add` in this plan requires explicit `@mentions`. A follow-up plan should add `TgCallbackQuery` handling, `answerCallbackQuery`/`editMessageText` to `TelegramApi`, and the picker/keyboard UI.
- `/members add <name>` (placeholder members with no Telegram identity) — implemented in Task 8, then removed after manual testing showed it was a dead end without the button-picker above: mention-based `/add` has no way to `@mention` someone with no `PlatformIdentity` row, so a placeholder member could be registered but never actually included in an expense. Revisit alongside the picker flow.
- Multi-currency `/balances` and `/list` ("one block per currency if the group has more than one") — this plan scopes both to the group's single `defaultCurrency`. Supporting more needs a new repository query (e.g. `ExpenseRepository.listCurrencies(groupId)`) that doesn't exist yet.
- Deliberately not touched — flagged in the earlier review but out of scope for "onboarding": `/balances`/`/list` only ever look at the group's current `defaultCurrency`, so expenses logged in another currency (via inline override, or before a `/currency` change) silently disappear from those views. That's a real behavior gap, not just copy, and needs its own decision on the fix (e.g. group by currency, or block currency changes once multi-currency expenses exist).
- Deployment (EC2/systemd) — covered by the persistence spec's "Deployment" section, not adapter code.
- WhatsApp, receipt OCR, natural-language entry, editing an existing expense — already out of scope per the bot design doc.

---

### Task 1: Storage prerequisites — username lookup and currency update

The adapter needs two things the current `core`/`storage` repository ports don't yet expose: resolving a Telegram `@username` mention to a `MemberId` (the schema only indexes `platform_identity` by numeric `external_user_id`), and updating a group's `defaultCurrency` for the `/currency` command (`GroupRepository` only has `create`/`find`/`addMember`). Both are small, additive, isolated changes — same shape as the audit-field additions already done in this codebase.

**Files:**
- Modify: `core/src/main/kotlin/split/core/Repositories.kt`
- Modify: `storage/src/main/resources/db/migration/V1__init.sql` — **do not touch**; add a new migration instead
- Create: `storage/src/main/resources/db/migration/V2__add_platform_identity_username.sql`
- Modify: `storage/src/main/kotlin/split/storage/Tables.kt`
- Modify: `storage/src/main/kotlin/split/storage/ExposedPlatformDirectory.kt`
- Modify: `storage/src/main/kotlin/split/storage/ExposedGroupRepository.kt`
- Test: `storage/src/test/kotlin/split/storage/ExposedPlatformDirectorySpec.kt`
- Test: `storage/src/test/kotlin/split/storage/ExposedGroupRepositorySpec.kt`

**Interfaces:**
- Produces: `GroupRepository.updateCurrency(id: GroupId, currency: String)`, `PlatformDirectory.findMemberByUsername(platform: String, username: String): MemberId?`, `PlatformDirectory.setUsername(platform: String, externalUserId: String, username: String)` — all `telegram`-module tasks depend on these.

- [ ] **Step 1: Write the failing tests**

Append to `storage/src/test/kotlin/split/storage/ExposedGroupRepositorySpec.kt`, inside the existing `StringSpec({ ... })` block, after the last test:

```kotlin
    "updateCurrency changes a group's default currency" {
        withTestDatabase { db ->
            val repo = ExposedGroupRepository(db)
            val group = Group(GroupId("g1"), "USD", Instant.parse("2026-08-27T00:00:00Z"))
            repo.create(group)

            repo.updateCurrency(GroupId("g1"), "EUR")

            repo.find(GroupId("g1")) shouldBe group.copy(defaultCurrency = "EUR")
        }
    }
```

Append to `storage/src/test/kotlin/split/storage/ExposedPlatformDirectorySpec.kt`, inside the existing `StringSpec({ ... })` block, after the last test:

```kotlin
    "finds a member by username after it's been set" {
        withTestDatabase { db ->
            ExposedMemberRepository(db).create(Member(MemberId("alice"), "Alice"))
            val directory = ExposedPlatformDirectory(db)
            directory.linkMember("telegram", "123456", MemberId("alice"))

            directory.setUsername("telegram", "123456", "alice_w")

            directory.findMemberByUsername("telegram", "alice_w") shouldBe MemberId("alice")
        }
    }

    "returns null for an unknown username" {
        withTestDatabase { db ->
            ExposedPlatformDirectory(db).findMemberByUsername("telegram", "nobody") shouldBe null
        }
    }
```

- [ ] **Step 2: Run tests to verify they fail to compile**

Run: `./gradlew :storage:test --tests "split.storage.ExposedGroupRepositorySpec" --tests "split.storage.ExposedPlatformDirectorySpec"`
Expected: compile error — `updateCurrency`, `setUsername`, `findMemberByUsername` are unresolved references.

- [ ] **Step 3: Extend the `core` repository ports**

In `core/src/main/kotlin/split/core/Repositories.kt`, replace the `GroupRepository` and `PlatformDirectory` interfaces:

```kotlin
interface GroupRepository {
    suspend fun create(group: Group)
    suspend fun find(id: GroupId): Group?
    suspend fun addMember(groupId: GroupId, memberId: MemberId)
    suspend fun updateCurrency(id: GroupId, currency: String)
}
```

```kotlin
interface PlatformDirectory {
    suspend fun findMember(platform: String, externalUserId: String): MemberId?
    suspend fun linkMember(platform: String, externalUserId: String, memberId: MemberId)
    suspend fun findMemberByUsername(platform: String, username: String): MemberId?
    suspend fun setUsername(platform: String, externalUserId: String, username: String)
    suspend fun findGroup(platform: String, externalChatId: String): GroupId?
    suspend fun linkGroup(platform: String, externalChatId: String, groupId: GroupId)
}
```

- [ ] **Step 4: Add the migration**

Create `storage/src/main/resources/db/migration/V2__add_platform_identity_username.sql`:

```sql
ALTER TABLE platform_identity ADD COLUMN username TEXT;

CREATE INDEX idx_platform_identity_username
    ON platform_identity(platform, username) WHERE username IS NOT NULL;
```

- [ ] **Step 5: Add the `username` column to `Tables.kt`**

In `storage/src/main/kotlin/split/storage/Tables.kt`, change:

```kotlin
object PlatformIdentityTable : Table("platform_identity") {
    val platform = text("platform")
    val externalUserId = text("external_user_id")
    val memberId = text("member_id").references(MemberTable.id)
    override val primaryKey = PrimaryKey(platform, externalUserId)
}
```

to:

```kotlin
object PlatformIdentityTable : Table("platform_identity") {
    val platform = text("platform")
    val externalUserId = text("external_user_id")
    val memberId = text("member_id").references(MemberTable.id)
    val username = text("username").nullable()
    override val primaryKey = PrimaryKey(platform, externalUserId)
}
```

- [ ] **Step 6: Implement `ExposedGroupRepository.updateCurrency`**

In `storage/src/main/kotlin/split/storage/ExposedGroupRepository.kt`, add the import `org.jetbrains.exposed.v1.jdbc.update` and add the method to the class:

```kotlin
    override suspend fun updateCurrency(id: GroupId, currency: String): Unit = withContext(Dispatchers.IO) {
        suspendTransaction(db) {
            GroupTable.update({ GroupTable.id eq id.value }) {
                it[defaultCurrency] = currency
            }
        }
    }
```

- [ ] **Step 7: Implement `ExposedPlatformDirectory.findMemberByUsername` and `setUsername`**

In `storage/src/main/kotlin/split/storage/ExposedPlatformDirectory.kt`, add the import `org.jetbrains.exposed.v1.jdbc.update` and add the two methods to the class:

```kotlin
    override suspend fun findMemberByUsername(platform: String, username: String): MemberId? =
        withContext(Dispatchers.IO) {
            suspendTransaction(db) {
                PlatformIdentityTable.selectAll()
                    .where {
                        (PlatformIdentityTable.platform eq platform) and
                            (PlatformIdentityTable.username eq username)
                    }
                    .map { MemberId(it[PlatformIdentityTable.memberId]) }
                    .singleOrNull()
            }
        }

    override suspend fun setUsername(platform: String, externalUserId: String, username: String): Unit =
        withContext(Dispatchers.IO) {
            suspendTransaction(db) {
                PlatformIdentityTable.update({
                    (PlatformIdentityTable.platform eq platform) and
                        (PlatformIdentityTable.externalUserId eq externalUserId)
                }) {
                    it[this.username] = username
                }
            }
        }
```

- [ ] **Step 8: Run tests to verify they pass**

Run: `./gradlew :core:test :storage:test`
Expected: `BUILD SUCCESSFUL`, all tests pass including the four new ones.

- [ ] **Step 9: Commit**

```bash
git add core/src/main/kotlin/split/core/Repositories.kt \
        storage/src/main/resources/db/migration/V2__add_platform_identity_username.sql \
        storage/src/main/kotlin/split/storage/Tables.kt \
        storage/src/main/kotlin/split/storage/ExposedGroupRepository.kt \
        storage/src/main/kotlin/split/storage/ExposedPlatformDirectory.kt \
        storage/src/test/kotlin/split/storage/ExposedGroupRepositorySpec.kt \
        storage/src/test/kotlin/split/storage/ExposedPlatformDirectorySpec.kt
git commit -m "Add username lookup and group currency update for the Telegram adapter"
```

---

### Task 2: `telegram` module scaffold and Telegram Bot API DTOs

**Files:**
- Modify: `settings.gradle.kts`
- Create: `telegram/build.gradle.kts`
- Create: `telegram/src/main/kotlin/split/telegram/TelegramDtos.kt`
- Test: `telegram/src/test/kotlin/split/telegram/TelegramDtosSpec.kt`

**Interfaces:**
- Produces: `TgUser`, `TgChat`, `TgMessage`, `TgUpdate`, `TgChatMember`, `GetUpdatesResponse`, `GetChatAdministratorsResponse`, `SendMessageRequest` — all later `telegram` tasks depend on these.

- [ ] **Step 1: Add the module to the Gradle build**

In `settings.gradle.kts`, add a third `include`:

```kotlin
rootProject.name = "split"

include("core")
include("storage")
include("telegram")
```

Create `telegram/build.gradle.kts`:

```kotlin
plugins {
    kotlin("jvm") version "2.4.0"
    kotlin("plugin.serialization") version "2.4.0"
}

repositories {
    mavenCentral()
}

val kotestVersion = "5.9.1"
val ktorVersion = "3.5.2"

dependencies {
    implementation(project(":core"))
    implementation(project(":storage"))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")

    testImplementation("io.kotest:kotest-runner-junit5:$kotestVersion")
    testImplementation("io.kotest:kotest-assertions-core:$kotestVersion")
    testImplementation("io.ktor:ktor-client-mock:$ktorVersion")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}
```

- [ ] **Step 2: Write the failing test**

Create `telegram/src/test/kotlin/split/telegram/TelegramDtosSpec.kt`:

```kotlin
package split.telegram

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json

class TelegramDtosSpec : StringSpec({

    val json = Json { ignoreUnknownKeys = true }

    "deserializes a getUpdates response with a text message" {
        val body = """
            {
              "ok": true,
              "result": [
                {
                  "update_id": 123456,
                  "message": {
                    "message_id": 42,
                    "from": {"id": 987654321, "first_name": "Alice", "username": "alice_w"},
                    "chat": {"id": -1001234567890, "type": "supergroup"},
                    "text": "/add 90 dinner @bob"
                  }
                }
              ]
            }
        """.trimIndent()

        val parsed = json.decodeFromString(GetUpdatesResponse.serializer(), body)

        parsed shouldBe GetUpdatesResponse(
            ok = true,
            result = listOf(
                TgUpdate(
                    updateId = 123456,
                    message = TgMessage(
                        messageId = 42,
                        from = TgUser(id = 987654321, firstName = "Alice", username = "alice_w"),
                        chat = TgChat(id = -1001234567890, type = "supergroup"),
                        text = "/add 90 dinner @bob",
                    ),
                ),
            ),
        )
    }

    "deserializes a getChatAdministrators response" {
        val body = """{"ok": true, "result": [{"status": "administrator", "user": {"id": 111, "first_name": "Bob"}}]}"""

        val parsed = json.decodeFromString(GetChatAdministratorsResponse.serializer(), body)

        parsed shouldBe GetChatAdministratorsResponse(
            ok = true,
            result = listOf(TgChatMember(status = "administrator", user = TgUser(id = 111, firstName = "Bob"))),
        )
    }

    "a message with no sender or text deserializes with nulls" {
        val body = """{"update_id": 1, "message": {"message_id": 1, "chat": {"id": 5, "type": "private"}}}"""

        val parsed = json.decodeFromString(TgUpdate.serializer(), body)

        parsed shouldBe TgUpdate(
            updateId = 1,
            message = TgMessage(messageId = 1, from = null, chat = TgChat(id = 5, type = "private"), text = null),
        )
    }
})
```

- [ ] **Step 2b: Run test to verify it fails**

Run: `./gradlew :telegram:test --tests "split.telegram.TelegramDtosSpec"`
Expected: FAIL — module/classes don't exist yet (build error: unresolved references `TgUpdate`, `GetUpdatesResponse`, etc.)

- [ ] **Step 3: Write the DTOs**

Create `telegram/src/main/kotlin/split/telegram/TelegramDtos.kt`:

```kotlin
package split.telegram

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TgUser(
    val id: Long,
    @SerialName("first_name") val firstName: String,
    val username: String? = null,
)

@Serializable
data class TgChat(
    val id: Long,
    val type: String,
)

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

@Serializable
data class TgChatMember(
    val status: String,
    val user: TgUser,
)

@Serializable
data class GetUpdatesResponse(
    val ok: Boolean,
    val result: List<TgUpdate> = emptyList(),
)

@Serializable
data class GetChatAdministratorsResponse(
    val ok: Boolean,
    val result: List<TgChatMember> = emptyList(),
)

@Serializable
data class SendMessageRequest(
    @SerialName("chat_id") val chatId: Long,
    val text: String,
    // No default value: kotlinx.serialization omits fields left at their default unless
    // encodeDefaults is set, and this one must always be sent.
    @SerialName("parse_mode") val parseMode: String,
)
```

(`parseMode` was added during the HTML-formatting review round after Task 16 — see the note near the end of this document.)

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :telegram:test --tests "split.telegram.TelegramDtosSpec"`
Expected: `BUILD SUCCESSFUL`, 3 tests pass.

- [ ] **Step 5: Commit**

```bash
git add settings.gradle.kts telegram/build.gradle.kts \
        telegram/src/main/kotlin/split/telegram/TelegramDtos.kt \
        telegram/src/test/kotlin/split/telegram/TelegramDtosSpec.kt
git commit -m "Add telegram module scaffold and Bot API DTOs"
```

---

### Task 3: `TelegramApi` interface and `HttpTelegramApi`

**Files:**
- Create: `telegram/src/main/kotlin/split/telegram/TelegramApi.kt`
- Create: `telegram/src/main/kotlin/split/telegram/HttpTelegramApi.kt`
- Test: `telegram/src/test/kotlin/split/telegram/HttpTelegramApiSpec.kt`

**Interfaces:**
- Consumes: `TgUpdate`, `TgChatMember`, `GetUpdatesResponse`, `GetChatAdministratorsResponse`, `SendMessageRequest` (Task 2).
- Produces: `interface TelegramApi { suspend fun getUpdates(offset: Long?, timeoutSeconds: Int): List<TgUpdate>; suspend fun sendMessage(chatId: Long, text: String); suspend fun getChatAdministrators(chatId: Long): List<TgChatMember> }`, `class HttpTelegramApi(botToken: String, baseUrl: String = "https://api.telegram.org", httpClient: HttpClient = <default Ktor CIO client with JSON content negotiation>) : TelegramApi`.

- [ ] **Step 1: Write the failing test**

Create `telegram/src/test/kotlin/split/telegram/HttpTelegramApiSpec.kt`. This uses `ktor-client-mock`'s `MockEngine` — the idiomatic way to test a Ktor client without a real network call, replacing what would otherwise be a local `HttpServer` stub:

```kotlin
package split.telegram

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import kotlinx.serialization.json.Json

class HttpTelegramApiSpec : StringSpec({

    fun clientReturning(body: String): Pair<HttpClient, MutableList<HttpRequestData>> {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            respond(
                content = ByteReadChannel(body),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        return client to requests
    }

    "getUpdates parses the response and requests the right path" {
        val (httpClient, requests) = clientReturning(
            """{"ok":true,"result":[{"update_id":1,"message":{"message_id":10,
                "from":{"id":99,"first_name":"Alice","username":"alice"},
                "chat":{"id":-100,"type":"group"},"text":"/help"}}]}""",
        )
        val api = HttpTelegramApi(botToken = "test-token", httpClient = httpClient)

        val updates = api.getUpdates(offset = null, timeoutSeconds = 0)

        updates shouldBe listOf(
            TgUpdate(
                updateId = 1,
                message = TgMessage(
                    messageId = 10,
                    from = TgUser(id = 99, firstName = "Alice", username = "alice"),
                    chat = TgChat(id = -100, type = "group"),
                    text = "/help",
                ),
            ),
        )
        requests.single().url.toString() shouldBe "https://api.telegram.org/bottest-token/getUpdates?timeout=0"
    }

    "getUpdates includes the offset when given" {
        val (httpClient, requests) = clientReturning("""{"ok":true,"result":[]}""")
        val api = HttpTelegramApi(botToken = "tok", httpClient = httpClient)

        api.getUpdates(offset = 42, timeoutSeconds = 5)

        requests.single().url.toString() shouldBe "https://api.telegram.org/bottok/getUpdates?timeout=5&offset=42"
    }

    "sendMessage posts chat_id and text as JSON" {
        val (httpClient, requests) = clientReturning("""{"ok":true}""")
        val api = HttpTelegramApi(botToken = "tok", httpClient = httpClient)

        api.sendMessage(chatId = -100, text = "hi")

        requests.single().body.toByteArray().decodeToString() shouldBe """{"chat_id":-100,"text":"hi","parse_mode":"HTML"}"""
    }

    "getChatAdministrators parses the response" {
        val (httpClient, _) = clientReturning(
            """{"ok":true,"result":[{"status":"creator","user":{"id":7,"first_name":"Owner"}}]}""",
        )
        val api = HttpTelegramApi(botToken = "tok", httpClient = httpClient)

        val admins = api.getChatAdministrators(chatId = -100)

        admins shouldBe listOf(TgChatMember(status = "creator", user = TgUser(id = 7, firstName = "Owner")))
    }
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :telegram:test --tests "split.telegram.HttpTelegramApiSpec"`
Expected: FAIL — `HttpTelegramApi` doesn't exist yet.

- [ ] **Step 3: Write the interface**

Create `telegram/src/main/kotlin/split/telegram/TelegramApi.kt`:

```kotlin
package split.telegram

interface TelegramApi {
    suspend fun getUpdates(offset: Long?, timeoutSeconds: Int): List<TgUpdate>
    suspend fun sendMessage(chatId: Long, text: String)
    suspend fun getChatAdministrators(chatId: Long): List<TgChatMember>
}
```

- [ ] **Step 4: Write the implementation**

Create `telegram/src/main/kotlin/split/telegram/HttpTelegramApi.kt`:

```kotlin
package split.telegram

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

class HttpTelegramApi(
    private val botToken: String,
    private val baseUrl: String = "https://api.telegram.org",
    private val httpClient: HttpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    },
) : TelegramApi {

    override suspend fun getUpdates(offset: Long?, timeoutSeconds: Int): List<TgUpdate> {
        val query = buildString {
            append("timeout=$timeoutSeconds")
            if (offset != null) append("&offset=$offset")
        }
        val response: GetUpdatesResponse = httpClient.get("$baseUrl/bot$botToken/getUpdates?$query").body()
        return response.result
    }

    override suspend fun sendMessage(chatId: Long, text: String) {
        httpClient.post("$baseUrl/bot$botToken/sendMessage") {
            contentType(ContentType.Application.Json)
            setBody(SendMessageRequest(chatId, text, parseMode = "HTML"))
        }
    }

    override suspend fun getChatAdministrators(chatId: Long): List<TgChatMember> {
        val response: GetChatAdministratorsResponse =
            httpClient.get("$baseUrl/bot$botToken/getChatAdministrators?chat_id=$chatId").body()
        return response.result
    }
}
```

Note: unlike `storage`'s JDBC calls, these Ktor client calls are not wrapped in `withContext(Dispatchers.IO)` — Ktor's `CIO` engine is coroutine-native (non-blocking NIO under the hood), so no thread-pool offload is needed.

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :telegram:test --tests "split.telegram.HttpTelegramApiSpec"`
Expected: `BUILD SUCCESSFUL`, 4 tests pass.

- [ ] **Step 6: Commit**

```bash
git add telegram/src/main/kotlin/split/telegram/TelegramApi.kt \
        telegram/src/main/kotlin/split/telegram/HttpTelegramApi.kt \
        telegram/src/test/kotlin/split/telegram/HttpTelegramApiSpec.kt
git commit -m "Add TelegramApi interface and HTTP implementation"
```

---

### Task 4: `IdentityResolver`

Resolves an incoming Telegram user/chat to a `core` `MemberId`/`GroupId`, creating them on first sight. With Telegram privacy mode left on (see the bot design doc's "Identity resolution" section), the only messages that ever reach the adapter are command messages — so "first sight" in practice means "the first command this user or chat ever sent," `/start` being the natural one but not the only one that counts.

**Files:**
- Create: `telegram/src/test/kotlin/split/telegram/TestDatabase.kt`
- Create: `telegram/src/main/kotlin/split/telegram/IdentityResolver.kt`
- Test: `telegram/src/test/kotlin/split/telegram/IdentityResolverSpec.kt`

**Interfaces:**
- Consumes: `MemberRepository`, `GroupRepository`, `PlatformDirectory` (`core`, extended in Task 1); `ExposedMemberRepository`, `ExposedGroupRepository`, `ExposedPlatformDirectory`, `connectDatabase` (`storage`).
- Produces: `class IdentityResolver(platformDirectory: PlatformDirectory, memberRepository: MemberRepository, groupRepository: GroupRepository) { suspend fun resolveMember(externalUserId: String, username: String?, displayName: String): MemberId; suspend fun resolveGroup(externalChatId: String): GroupId; suspend fun ensureGroupMembership(groupId: GroupId, memberId: MemberId) }`, companion `IdentityResolver.PLATFORM = "telegram"`. `withTestDatabase` (test-only) for all later `telegram` tests that touch repositories.

- [ ] **Step 1: Add the test database helper**

Create `telegram/src/test/kotlin/split/telegram/TestDatabase.kt`:

```kotlin
package split.telegram

import org.jetbrains.exposed.v1.jdbc.Database
import split.storage.connectDatabase
import java.nio.file.Files

/** Mirrors `storage`'s `withTestDatabase` — a real temp-file SQLite database migrated
 * through the actual Flyway path, since `storage`'s test source set isn't visible here. */
suspend fun withTestDatabase(test: suspend (Database) -> Unit) {
    val dbFile = Files.createTempFile("split-telegram-test-", ".db")
    Files.delete(dbFile)
    val path = dbFile.toString()

    try {
        test(connectDatabase(path))
    } finally {
        Files.deleteIfExists(dbFile)
        Files.deleteIfExists(dbFile.resolveSibling(dbFile.fileName.toString() + "-wal"))
        Files.deleteIfExists(dbFile.resolveSibling(dbFile.fileName.toString() + "-shm"))
    }
}
```

- [ ] **Step 2: Write the failing test**

Create `telegram/src/test/kotlin/split/telegram/IdentityResolverSpec.kt`:

```kotlin
package split.telegram

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import split.core.Member
import split.storage.ExposedGroupRepository
import split.storage.ExposedMemberRepository
import split.storage.ExposedPlatformDirectory

class IdentityResolverSpec : StringSpec({

    "creates a new member on first sight and reuses it afterwards" {
        withTestDatabase { db ->
            val resolver = IdentityResolver(
                ExposedPlatformDirectory(db),
                ExposedMemberRepository(db),
                ExposedGroupRepository(db),
            )

            val first = resolver.resolveMember("123", "alice_w", "Alice")
            val second = resolver.resolveMember("123", "alice_w", "Alice")

            first shouldBe second
        }
    }

    "records the username so it can be looked up later" {
        withTestDatabase { db ->
            val directory = ExposedPlatformDirectory(db)
            val resolver = IdentityResolver(directory, ExposedMemberRepository(db), ExposedGroupRepository(db))

            val memberId = resolver.resolveMember("123", "alice_w", "Alice")

            directory.findMemberByUsername("telegram", "alice_w") shouldBe memberId
        }
    }

    "creates a new group on first sight and reuses it afterwards" {
        withTestDatabase { db ->
            val resolver = IdentityResolver(
                ExposedPlatformDirectory(db),
                ExposedMemberRepository(db),
                ExposedGroupRepository(db),
            )

            val first = resolver.resolveGroup("-100")
            val second = resolver.resolveGroup("-100")

            first shouldBe second
        }
    }

    "ensureGroupMembership adds a member to the group only once" {
        withTestDatabase { db ->
            val memberRepository = ExposedMemberRepository(db)
            val resolver = IdentityResolver(
                ExposedPlatformDirectory(db),
                memberRepository,
                ExposedGroupRepository(db),
            )

            val memberId = resolver.resolveMember("123", "alice_w", "Alice")
            val groupId = resolver.resolveGroup("-100")

            resolver.ensureGroupMembership(groupId, memberId)
            resolver.ensureGroupMembership(groupId, memberId)

            memberRepository.findByGroup(groupId) shouldBe listOf(Member(memberId, "Alice"))
        }
    }
})
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :telegram:test --tests "split.telegram.IdentityResolverSpec"`
Expected: FAIL — `IdentityResolver` doesn't exist yet.

- [ ] **Step 4: Write the implementation**

Create `telegram/src/main/kotlin/split/telegram/IdentityResolver.kt`:

```kotlin
package split.telegram

import split.core.Group
import split.core.GroupId
import split.core.GroupRepository
import split.core.Member
import split.core.MemberId
import split.core.MemberRepository
import split.core.PlatformDirectory
import java.time.Clock
import java.time.Instant
import java.util.UUID

class IdentityResolver(
    private val platformDirectory: PlatformDirectory,
    private val memberRepository: MemberRepository,
    private val groupRepository: GroupRepository,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val clock: Clock = Clock.systemUTC(),
) {

    suspend fun resolveMember(externalUserId: String, username: String?, displayName: String): MemberId {
        val existing = platformDirectory.findMember(PLATFORM, externalUserId)
        val memberId = if (existing != null) {
            existing
        } else {
            val newId = MemberId(idGenerator())
            memberRepository.create(Member(newId, displayName))
            platformDirectory.linkMember(PLATFORM, externalUserId, newId)
            newId
        }
        if (username != null) {
            platformDirectory.setUsername(PLATFORM, externalUserId, username)
        }
        return memberId
    }

    suspend fun resolveGroup(externalChatId: String): GroupId {
        val existing = platformDirectory.findGroup(PLATFORM, externalChatId)
        if (existing != null) return existing

        val newId = GroupId(idGenerator())
        groupRepository.create(Group(newId, DEFAULT_CURRENCY, Instant.now(clock)))
        platformDirectory.linkGroup(PLATFORM, externalChatId, newId)
        return newId
    }

    suspend fun ensureGroupMembership(groupId: GroupId, memberId: MemberId) {
        val members = memberRepository.findByGroup(groupId)
        if (members.none { it.id == memberId }) {
            groupRepository.addMember(groupId, memberId)
        }
    }

    companion object {
        const val PLATFORM = "telegram"
        const val DEFAULT_CURRENCY = "USD"
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :telegram:test --tests "split.telegram.IdentityResolverSpec"`
Expected: `BUILD SUCCESSFUL`, 4 tests pass.

- [ ] **Step 6: Commit**

```bash
git add telegram/src/test/kotlin/split/telegram/TestDatabase.kt \
        telegram/src/main/kotlin/split/telegram/IdentityResolver.kt \
        telegram/src/test/kotlin/split/telegram/IdentityResolverSpec.kt
git commit -m "Add IdentityResolver for Telegram user/chat to core id resolution"
```

---

### Task 5: Command text parsing

**Files:**
- Create: `telegram/src/main/kotlin/split/telegram/CommandParsing.kt`
- Test: `telegram/src/test/kotlin/split/telegram/CommandParsingSpec.kt`

**Interfaces:**
- Produces: `fun parseCommand(text: String): Pair<String, String>`, `fun extractMentions(text: String): List<String>` — consumed by `CommandRouter` (Task 6) and later `/add`/`/settle` argument parsing (Tasks 10, 14).

- [ ] **Step 1: Write the failing test**

Create `telegram/src/test/kotlin/split/telegram/CommandParsingSpec.kt`:

```kotlin
package split.telegram

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class CommandParsingSpec : StringSpec({

    "parses a bare command with no args" {
        parseCommand("/help") shouldBe ("help" to "")
    }

    "parses a command with args" {
        parseCommand("/add 90 dinner @alice") shouldBe ("add" to "90 dinner @alice")
    }

    "strips a bot-name suffix like /add@mybot" {
        parseCommand("/add@mybot 90 dinner") shouldBe ("add" to "90 dinner")
    }

    "lowercases the command name" {
        parseCommand("/ADD 90 dinner") shouldBe ("add" to "90 dinner")
    }

    "extracts multiple mentions" {
        extractMentions("split with @alice_w and @bob99") shouldBe listOf("alice_w", "bob99")
    }

    "returns no mentions when there are none" {
        extractMentions("just a plain description") shouldBe emptyList()
    }
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :telegram:test --tests "split.telegram.CommandParsingSpec"`
Expected: FAIL — `parseCommand`/`extractMentions` don't exist yet.

- [ ] **Step 3: Write the implementation**

Create `telegram/src/main/kotlin/split/telegram/CommandParsing.kt`:

```kotlin
package split.telegram

internal val mentionPattern = Regex("@([a-zA-Z][a-zA-Z0-9_]{4,31})")

fun parseCommand(text: String): Pair<String, String> {
    val trimmed = text.trim()
    val spaceIndex = trimmed.indexOf(' ')
    val rawCommand = if (spaceIndex == -1) trimmed else trimmed.substring(0, spaceIndex)
    val args = if (spaceIndex == -1) "" else trimmed.substring(spaceIndex + 1).trim()
    val command = rawCommand.removePrefix("/").substringBefore('@').lowercase()
    return command to args
}

fun extractMentions(text: String): List<String> =
    mentionPattern.findAll(text).map { it.groupValues[1] }.toList()
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :telegram:test --tests "split.telegram.CommandParsingSpec"`
Expected: `BUILD SUCCESSFUL`, 6 tests pass.

- [ ] **Step 5: Commit**

```bash
git add telegram/src/main/kotlin/split/telegram/CommandParsing.kt \
        telegram/src/test/kotlin/split/telegram/CommandParsingSpec.kt
git commit -m "Add Telegram command text parsing"
```

---

### Task 6: `CommandRouter`, `FakeTelegramApi`, `/help` and `/start`

Establishes the dispatch pattern every later command task plugs into.

**Files:**
- Create: `telegram/src/test/kotlin/split/telegram/FakeTelegramApi.kt`
- Create: `telegram/src/main/kotlin/split/telegram/CommandRouter.kt`
- Create: `telegram/src/main/kotlin/split/telegram/HelpCommand.kt`
- Test: `telegram/src/test/kotlin/split/telegram/CommandRouterSpec.kt`

**Interfaces:**
- Consumes: `TelegramApi`, `TgUpdate`/`TgMessage`/`TgUser`/`TgChat` (Tasks 2–3), `IdentityResolver` (Task 4), `parseCommand` (Task 5).
- Produces: `data class CommandContext(chatId: Long, memberId: MemberId, externalUserId: String, groupId: GroupId, args: String)`, `typealias CommandHandler = suspend (CommandContext) -> Unit`, `class CommandRouter(identityResolver: IdentityResolver, handlers: Map<String, CommandHandler>) { suspend fun handleUpdate(update: TgUpdate) }`, `class HelpCommand(telegramApi: TelegramApi) { suspend fun handle(context: CommandContext) }`, `class StartCommand(telegramApi: TelegramApi) { suspend fun handle(context: CommandContext) }`, `internal const val HELP_TEXT: String`. `FakeTelegramApi` (test-only) consumed by every later command-handler test.

- [ ] **Step 1: Add the fake Telegram API test double**

Create `telegram/src/test/kotlin/split/telegram/FakeTelegramApi.kt`:

```kotlin
package split.telegram

class FakeTelegramApi : TelegramApi {
    val sentMessages = mutableListOf<Pair<Long, String>>()
    var chatAdministrators: List<TgChatMember> = emptyList()
    var updatesToReturn: List<TgUpdate> = emptyList()

    override suspend fun getUpdates(offset: Long?, timeoutSeconds: Int): List<TgUpdate> = updatesToReturn

    override suspend fun sendMessage(chatId: Long, text: String) {
        sentMessages += chatId to text
    }

    override suspend fun getChatAdministrators(chatId: Long): List<TgChatMember> = chatAdministrators
}
```

- [ ] **Step 2: Write the failing test**

Create `telegram/src/test/kotlin/split/telegram/CommandRouterSpec.kt`:

```kotlin
package split.telegram

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import split.storage.ExposedGroupRepository
import split.storage.ExposedMemberRepository
import split.storage.ExposedPlatformDirectory

class CommandRouterSpec : StringSpec({

    fun aResolver(db: org.jetbrains.exposed.v1.jdbc.Database) = IdentityResolver(
        ExposedPlatformDirectory(db),
        ExposedMemberRepository(db),
        ExposedGroupRepository(db),
    )

    "dispatches /help to the help handler" {
        withTestDatabase { db ->
            val telegramApi = FakeTelegramApi()
            val router = CommandRouter(aResolver(db), mapOf("help" to HelpCommand(telegramApi)::handle))

            router.handleUpdate(
                TgUpdate(
                    updateId = 1,
                    message = TgMessage(
                        messageId = 1,
                        from = TgUser(id = 1, firstName = "Alice"),
                        chat = TgChat(id = -1, type = "group"),
                        text = "/help",
                    ),
                ),
            )

            telegramApi.sentMessages shouldBe listOf(-1L to HELP_TEXT)
        }
    }

    "dispatches /start to the start handler" {
        withTestDatabase { db ->
            val telegramApi = FakeTelegramApi()
            val router = CommandRouter(aResolver(db), mapOf("start" to StartCommand(telegramApi)::handle))

            router.handleUpdate(
                TgUpdate(
                    updateId = 1,
                    message = TgMessage(
                        messageId = 1,
                        from = TgUser(id = 1, firstName = "Alice"),
                        chat = TgChat(id = -1, type = "group"),
                        text = "/start",
                    ),
                ),
            )

            telegramApi.sentMessages.single().first shouldBe -1L
        }
    }

    "ignores non-command text" {
        withTestDatabase { db ->
            val telegramApi = FakeTelegramApi()
            val router = CommandRouter(aResolver(db), mapOf("help" to HelpCommand(telegramApi)::handle))

            router.handleUpdate(
                TgUpdate(
                    updateId = 1,
                    message = TgMessage(
                        messageId = 1,
                        from = TgUser(id = 1, firstName = "Alice"),
                        chat = TgChat(id = -1, type = "group"),
                        text = "just chatting",
                    ),
                ),
            )

            telegramApi.sentMessages shouldBe emptyList()
        }
    }

    "ignores updates with no message or no sender" {
        withTestDatabase { db ->
            val telegramApi = FakeTelegramApi()
            val router = CommandRouter(aResolver(db), mapOf("help" to HelpCommand(telegramApi)::handle))

            router.handleUpdate(TgUpdate(updateId = 1, message = null))

            telegramApi.sentMessages shouldBe emptyList()
        }
    }
})
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :telegram:test --tests "split.telegram.CommandRouterSpec"`
Expected: FAIL — `CommandRouter`, `HelpCommand`, `StartCommand`, `CommandContext` don't exist yet.

- [ ] **Step 4: Write `CommandRouter`**

Create `telegram/src/main/kotlin/split/telegram/CommandRouter.kt`:

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

typealias CommandHandler = suspend (CommandContext) -> Unit

class CommandRouter(
    private val identityResolver: IdentityResolver,
    private val handlers: Map<String, CommandHandler>,
) {
    suspend fun handleUpdate(update: TgUpdate) {
        val message = update.message ?: return
        val from = message.from ?: return
        val text = message.text ?: return

        if (!text.startsWith("/")) return

        val (command, args) = parseCommand(text)
        val handler = handlers[command] ?: return

        val memberId = identityResolver.resolveMember(from.id.toString(), from.username, from.firstName)
        val groupId = identityResolver.resolveGroup(message.chat.id.toString())
        identityResolver.ensureGroupMembership(groupId, memberId)

        handler(CommandContext(message.chat.id, memberId, from.id.toString(), groupId, args))
    }
}
```

Note: identity resolution happens only after a real, registered handler is found — not for every incoming message. With privacy mode on (see the bot design spec's "Identity resolution" section), non-command group chatter never reaches the adapter at all, but private chats deliver every message regardless of privacy mode, so the router itself must not treat arbitrary text as a registration trigger either.

- [ ] **Step 5: Write `HelpCommand` and `StartCommand`**

Create `telegram/src/main/kotlin/split/telegram/HelpCommand.kt`:

```kotlin
package split.telegram

// Sent with parse_mode HTML (see HttpTelegramApi), so placeholders use <code>...</code>
// rather than bare <angle brackets> — a raw "<amount>" would be read as an (invalid,
// unclosed) HTML tag and mangle or break the message. (Added during the HTML-formatting
// review round after Task 16 — see the note near the end of this document.)
internal const val HELP_TEXT = """Commands:
/add <code>amount</code> [<code>currency</code>] <code>description</code> <code>@mentions...</code> — log an expense you paid, split equally
/members — list who I recognize in this group
/currency <code>currency</code> — set this group's default currency
/balances — see who owes you and who you owe
/list — last 10 expenses
/delete <code>id</code> — remove an expense (payer or admin only)
/settle <code>@person</code> <code>amount</code> — record that you paid them
/settle_suggest — minimal set of payments to settle the group up
/help — this message"""

class HelpCommand(private val telegramApi: TelegramApi) {
    suspend fun handle(context: CommandContext) {
        telegramApi.sendMessage(context.chatId, HELP_TEXT)
    }
}

class StartCommand(private val telegramApi: TelegramApi) {
    suspend fun handle(context: CommandContext) {
        telegramApi.sendMessage(context.chatId, "Hi! I'll help you split expenses in this group.\n\n$HELP_TEXT")
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew :telegram:test --tests "split.telegram.CommandRouterSpec"`
Expected: `BUILD SUCCESSFUL`, 4 tests pass.

- [ ] **Step 7: Commit**

```bash
git add telegram/src/test/kotlin/split/telegram/FakeTelegramApi.kt \
        telegram/src/main/kotlin/split/telegram/CommandRouter.kt \
        telegram/src/main/kotlin/split/telegram/HelpCommand.kt \
        telegram/src/test/kotlin/split/telegram/CommandRouterSpec.kt
git commit -m "Add CommandRouter with /help and /start commands"
```

---

### Task 7: `/currency` command

**Files:**
- Create: `telegram/src/main/kotlin/split/telegram/CurrencyCommand.kt`
- Test: `telegram/src/test/kotlin/split/telegram/CurrencyCommandSpec.kt`

**Interfaces:**
- Consumes: `GroupRepository.updateCurrency` (Task 1), `CommandContext`, `TelegramApi`, `IdentityResolver` (Task 4/6), `ExposedGroupRepository`.
- Produces: `class CurrencyCommand(groupRepository: GroupRepository, telegramApi: TelegramApi) { suspend fun handle(context: CommandContext) }`.

- [ ] **Step 1: Write the failing test**

Create `telegram/src/test/kotlin/split/telegram/CurrencyCommandSpec.kt`:

```kotlin
package split.telegram

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import split.core.MemberId
import split.storage.ExposedGroupRepository
import split.storage.ExposedMemberRepository
import split.storage.ExposedPlatformDirectory

class CurrencyCommandSpec : StringSpec({

    "sets the group's default currency" {
        withTestDatabase { db ->
            val groupRepository = ExposedGroupRepository(db)
            val resolver = IdentityResolver(ExposedPlatformDirectory(db), ExposedMemberRepository(db), groupRepository)
            val groupId = resolver.resolveGroup("-100")
            val telegramApi = FakeTelegramApi()
            val command = CurrencyCommand(groupRepository, telegramApi)

            command.handle(CommandContext(-100, MemberId("m1"), "1", groupId, "EUR"))

            groupRepository.find(groupId)!!.defaultCurrency shouldBe "EUR"
            telegramApi.sentMessages shouldBe listOf(-100L to "This group's default currency is now EUR.")
        }
    }

    "rejects an invalid currency code" {
        withTestDatabase { db ->
            val groupRepository = ExposedGroupRepository(db)
            val resolver = IdentityResolver(ExposedPlatformDirectory(db), ExposedMemberRepository(db), groupRepository)
            val groupId = resolver.resolveGroup("-100")
            val telegramApi = FakeTelegramApi()
            val command = CurrencyCommand(groupRepository, telegramApi)

            command.handle(CommandContext(-100, MemberId("m1"), "1", groupId, "not a code"))

            groupRepository.find(groupId)!!.defaultCurrency shouldBe IdentityResolver.DEFAULT_CURRENCY
            telegramApi.sentMessages shouldBe listOf(-100L to "Usage: /currency <code>currency</code>, e.g. /currency EUR")
        }
    }
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :telegram:test --tests "split.telegram.CurrencyCommandSpec"`
Expected: FAIL — `CurrencyCommand` doesn't exist yet.

- [ ] **Step 3: Write the implementation**

Create `telegram/src/main/kotlin/split/telegram/CurrencyCommand.kt`:

```kotlin
package split.telegram

import split.core.GroupRepository

class CurrencyCommand(
    private val groupRepository: GroupRepository,
    private val telegramApi: TelegramApi,
) {
    suspend fun handle(context: CommandContext) {
        val code = context.args.trim().uppercase()
        if (!Regex("^[A-Z]{3}$").matches(code)) {
            telegramApi.sendMessage(context.chatId, "Usage: /currency <code>currency</code>, e.g. /currency EUR")
            return
        }
        groupRepository.updateCurrency(context.groupId, code)
        telegramApi.sendMessage(context.chatId, "This group's default currency is now $code.")
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :telegram:test --tests "split.telegram.CurrencyCommandSpec"`
Expected: `BUILD SUCCESSFUL`, 2 tests pass.

- [ ] **Step 5: Commit**

```bash
git add telegram/src/main/kotlin/split/telegram/CurrencyCommand.kt \
        telegram/src/test/kotlin/split/telegram/CurrencyCommandSpec.kt
git commit -m "Add /currency command"
```

---

### Task 8: `/members` command

**Files:**
- Create: `telegram/src/main/kotlin/split/telegram/MembersCommand.kt`
- Test: `telegram/src/test/kotlin/split/telegram/MembersCommandSpec.kt`

**Interfaces:**
- Consumes: `MemberRepository` (`core`), `CommandContext`, `TelegramApi`.
- Produces: `class MembersCommand(memberRepository: MemberRepository, telegramApi: TelegramApi) { suspend fun handle(context: CommandContext) }`.

Note: `/members add <name>` (placeholder members with no Telegram identity) was implemented, then removed after manual live-bot testing — mention-based `/add` (this plan's only `/add` flow) can never include a placeholder member, since there's no `@username` to mention for someone with no `PlatformIdentity` row. That made `/members add` a dead end: you could register a placeholder, but never actually split an expense with them. Placeholder members only become usable once the button-based participant picker ships (see "Out of scope"), so `/members add` is deferred alongside it rather than shipped as a trap. `/members` itself (listing) stays.

- [ ] **Step 1: Write the failing test**

Create `telegram/src/test/kotlin/split/telegram/MembersCommandSpec.kt`:

```kotlin
package split.telegram

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import split.core.MemberId
import split.storage.ExposedGroupRepository
import split.storage.ExposedMemberRepository
import split.storage.ExposedPlatformDirectory

class MembersCommandSpec : StringSpec({

    "lists no members when the group is empty" {
        withTestDatabase { db ->
            val groupRepository = ExposedGroupRepository(db)
            val resolver = IdentityResolver(ExposedPlatformDirectory(db), ExposedMemberRepository(db), groupRepository)
            val groupId = resolver.resolveGroup("-100001")
            val telegramApi = FakeTelegramApi()
            val command = MembersCommand(ExposedMemberRepository(db), telegramApi)

            command.handle(CommandContext(-100, MemberId("m1"), "1", groupId, ""))

            telegramApi.sentMessages shouldBe listOf(-100L to "No members yet.")
        }
    }

    "lists members already in the group" {
        withTestDatabase { db ->
            val groupRepository = ExposedGroupRepository(db)
            val memberRepository = ExposedMemberRepository(db)
            val resolver = IdentityResolver(ExposedPlatformDirectory(db), memberRepository, groupRepository)
            val memberId = resolver.resolveMember("1", "alice", "Alice")
            val groupId = resolver.resolveGroup("-100001")
            resolver.ensureGroupMembership(groupId, memberId)
            val telegramApi = FakeTelegramApi()
            val command = MembersCommand(memberRepository, telegramApi)

            command.handle(CommandContext(-100, memberId, "1", groupId, ""))

            telegramApi.sentMessages shouldBe listOf(-100L to "• Alice")
        }
    }
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :telegram:test --tests "split.telegram.MembersCommandSpec"`
Expected: FAIL — `MembersCommand` doesn't exist yet.

- [ ] **Step 3: Write the implementation**

Create `telegram/src/main/kotlin/split/telegram/MembersCommand.kt`:

```kotlin
package split.telegram

import split.core.Member
import split.core.MemberRepository

class MembersCommand(
    private val memberRepository: MemberRepository,
    private val telegramApi: TelegramApi,
) {
    suspend fun handle(context: CommandContext) {
        val members = memberRepository.findByGroup(context.groupId)
        telegramApi.sendMessage(context.chatId, formatMembers(members))
    }
}

internal fun formatMembers(members: List<Member>): String {
    if (members.isEmpty()) return "No members yet."
    return members.joinToString("\n") { "• ${escapeHtml(it.displayName)}" }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :telegram:test --tests "split.telegram.MembersCommandSpec"`
Expected: `BUILD SUCCESSFUL`, 2 tests pass.

- [ ] **Step 5: Commit**

```bash
git add telegram/src/main/kotlin/split/telegram/MembersCommand.kt \
        telegram/src/test/kotlin/split/telegram/MembersCommandSpec.kt
git commit -m "Add /members command"
```

---

### Task 9: Message formatting for expenses, balances, and settle-suggest

Pure functions — no repositories, no Telegram API — kept separate from `MembersCommand.kt`'s `formatMembers` (Task 8) because these need `core`'s money/debt types.

**Files:**
- Create: `telegram/src/main/kotlin/split/telegram/MessageFormatting.kt`
- Test: `telegram/src/test/kotlin/split/telegram/MessageFormattingSpec.kt`

**Interfaces:**
- Consumes: `Expense`, `Member`, `MemberId`, `DebtPayment` (`core`).
- Produces: `fun formatAmount(amount: BigDecimal, currency: String): String`, `internal fun escapeHtml(text: String): String`, `fun formatExpenseConfirmation(expense: Expense, members: List<Member>): String`, `fun formatExpenseList(expenses: List<Expense>, members: List<Member>): String`, `fun formatBalances(payments: List<DebtPayment>, members: List<Member>, viewerId: MemberId, currency: String): String`, `fun formatSettleSuggestions(payments: List<DebtPayment>, members: List<Member>, currency: String): String` — consumed by Tasks 10–15. `escapeHtml` (added during the HTML-formatting review round after Task 16, since `telegramApi.sendMessage` now sends `parse_mode: HTML`) is also used directly by `MembersCommand.kt` and `DeleteExpenseCommand.kt` for user-controlled text they format outside this file. Note `currency` is a separate parameter on `formatBalances`/`formatSettleSuggestions` because `DebtPayment` (from `core`'s `simplifyDebts`) doesn't carry a currency field — callers already have it in scope from the `(group, currency)` they computed balances for. `formatExpenseConfirmation`/`formatExpenseList` don't need it as a parameter since `Expense` already carries its own `currency`. `formatExpenseList` takes `members` (added during review, alongside Task 12) so each line shows who paid and who participated, not just the amount.

- [ ] **Step 1: Write the failing test**

Create `telegram/src/test/kotlin/split/telegram/MessageFormattingSpec.kt`:

```kotlin
package split.telegram

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant
import split.core.DebtPayment
import split.core.Expense
import split.core.ExpenseId
import split.core.ExpenseShare
import split.core.GroupId
import split.core.Member
import split.core.MemberId
import split.core.SplitType

class MessageFormattingSpec : StringSpec({

    val alice = Member(MemberId("alice"), "Alice")
    val bob = Member(MemberId("bob"), "Bob")
    val members = listOf(alice, bob)

    "formatAmount renders two decimal places with the currency code" {
        formatAmount(BigDecimal("90"), "USD") shouldBe "90.00 USD"
        formatAmount(BigDecimal("12.5"), "EUR") shouldBe "12.50 EUR"
    }

    "formatExpenseConfirmation names payer, amount, currency, and split type, without repeating the payer" {
        val expense = Expense(
            id = ExpenseId("e1"),
            groupId = GroupId("g1"),
            currency = "EUR",
            description = "dinner",
            amount = BigDecimal("90.00"),
            payerId = alice.id,
            splitType = SplitType.EQUAL,
            createdBy = alice.id,
            createdAt = Instant.parse("2026-08-28T00:00:00Z"),
            shares = listOf(ExpenseShare(alice.id, BigDecimal("45.00")), ExpenseShare(bob.id, BigDecimal("45.00"))),
        )

        formatExpenseConfirmation(expense, members) shouldBe "Alice paid 90.00 EUR for <b>dinner</b>, split equally with Bob"
    }

    "formatExpenseConfirmation names the split type for EXACT and SHARES splits too" {
        val exact = Expense(
            id = ExpenseId("e1"),
            groupId = GroupId("g1"),
            currency = "USD",
            description = "dinner",
            amount = BigDecimal("90.00"),
            payerId = alice.id,
            splitType = SplitType.EXACT,
            createdBy = alice.id,
            createdAt = Instant.parse("2026-08-28T00:00:00Z"),
            shares = listOf(ExpenseShare(alice.id, BigDecimal("50.00")), ExpenseShare(bob.id, BigDecimal("40.00"))),
        )
        val shares = exact.copy(splitType = SplitType.SHARES)

        formatExpenseConfirmation(exact, members) shouldBe "Alice paid 90.00 USD for <b>dinner</b>, split by exact amounts with Bob"
        formatExpenseConfirmation(shares, members) shouldBe "Alice paid 90.00 USD for <b>dinner</b>, split by shares with Bob"
    }

    "formatExpenseConfirmation omits the split clause entirely when the payer is the only participant" {
        val expense = Expense(
            id = ExpenseId("e1"),
            groupId = GroupId("g1"),
            currency = "USD",
            description = "solo lunch",
            amount = BigDecimal("12.00"),
            payerId = alice.id,
            splitType = SplitType.EQUAL,
            createdBy = alice.id,
            createdAt = Instant.parse("2026-08-28T00:00:00Z"),
            shares = listOf(ExpenseShare(alice.id, BigDecimal("12.00"))),
        )

        formatExpenseConfirmation(expense, members) shouldBe "Alice paid 12.00 USD for <b>solo lunch</b>"
    }

    "formatExpenseConfirmation fails loudly if the payer isn't in the members list" {
        val expense = Expense(
            id = ExpenseId("e1"),
            groupId = GroupId("g1"),
            currency = "USD",
            description = "dinner",
            amount = BigDecimal("90.00"),
            payerId = MemberId("not-a-member"),
            splitType = SplitType.EQUAL,
            createdBy = alice.id,
            createdAt = Instant.parse("2026-08-28T00:00:00Z"),
            shares = listOf(ExpenseShare(alice.id, BigDecimal("90.00"))),
        )

        shouldThrow<NoSuchElementException> { formatExpenseConfirmation(expense, members) }
    }

    "formatExpenseList shows a short id, date, and per-person share breakdown for an equal split" {
        val expense = Expense(
            id = ExpenseId("abcdef1234567890"),
            groupId = GroupId("g1"),
            currency = "USD",
            description = "dinner",
            amount = BigDecimal("90.00"),
            payerId = alice.id,
            splitType = SplitType.EQUAL,
            createdBy = alice.id,
            createdAt = Instant.parse("2026-08-28T14:30:00Z"),
            shares = listOf(ExpenseShare(alice.id, BigDecimal("45.00")), ExpenseShare(bob.id, BigDecimal("45.00"))),
        )

        formatExpenseList(listOf(expense), members) shouldBe
            "<b>Last 10 expenses:</b>\n\n" +
            "<code>abcdef12</code>  2026-08-28  <b>dinner</b>  90.00 USD\n" +
            "paid by Alice, split equally: Alice 45.00 USD, Bob 45.00 USD"
    }

    "formatExpenseList shows the real per-person amounts for an exact split, not an equal guess" {
        val expense = Expense(
            id = ExpenseId("abcdef1234567890"),
            groupId = GroupId("g1"),
            currency = "USD",
            description = "rent",
            amount = BigDecimal("90.00"),
            payerId = alice.id,
            splitType = SplitType.EXACT,
            createdBy = alice.id,
            createdAt = Instant.parse("2026-08-28T00:00:00Z"),
            shares = listOf(ExpenseShare(alice.id, BigDecimal("50.00")), ExpenseShare(bob.id, BigDecimal("40.00"))),
        )

        formatExpenseList(listOf(expense), members) shouldBe
            "<b>Last 10 expenses:</b>\n\n" +
            "<code>abcdef12</code>  2026-08-28  <b>rent</b>  90.00 USD\n" +
            "paid by Alice, split by exact amounts: Alice 50.00 USD, Bob 40.00 USD"
    }

    "formatExpenseList shows the real per-person amounts for a shares split" {
        val expense = Expense(
            id = ExpenseId("abcdef1234567890"),
            groupId = GroupId("g1"),
            currency = "USD",
            description = "groceries",
            amount = BigDecimal("90.00"),
            payerId = alice.id,
            splitType = SplitType.SHARES,
            createdBy = alice.id,
            createdAt = Instant.parse("2026-08-28T00:00:00Z"),
            shares = listOf(ExpenseShare(alice.id, BigDecimal("60.00")), ExpenseShare(bob.id, BigDecimal("30.00"))),
        )

        formatExpenseList(listOf(expense), members) shouldBe
            "<b>Last 10 expenses:</b>\n\n" +
            "<code>abcdef12</code>  2026-08-28  <b>groceries</b>  90.00 USD\n" +
            "paid by Alice, split by shares: Alice 60.00 USD, Bob 30.00 USD"
    }

    "formatExpenseList explains there's nothing yet" {
        formatExpenseList(emptyList(), members) shouldBe "No expenses yet — use /add to log one."
    }

    "formatBalances phrases payments relative to the viewer" {
        val payments = listOf(DebtPayment(from = bob.id, to = alice.id, amount = BigDecimal("30.00")))

        formatBalances(payments, members, viewerId = alice.id, currency = "USD") shouldBe "Bob owes you 30.00 USD"
        formatBalances(payments, members, viewerId = bob.id, currency = "USD") shouldBe "You owe Alice 30.00 USD"
    }

    "formatBalances says everyone's settled up when there's nothing relevant" {
        formatBalances(emptyList(), members, viewerId = alice.id, currency = "USD") shouldBe "You're all settled up!"
    }

    "formatBalances fails loudly if a payment references a member outside the group" {
        val payments = listOf(DebtPayment(from = MemberId("not-a-member"), to = alice.id, amount = BigDecimal("30.00")))

        shouldThrow<NoSuchElementException> { formatBalances(payments, members, viewerId = alice.id, currency = "USD") }
    }

    "formatSettleSuggestions lists every payment" {
        val payments = listOf(DebtPayment(from = bob.id, to = alice.id, amount = BigDecimal("30.00")))

        formatSettleSuggestions(payments, members, currency = "USD") shouldBe "Bob pays Alice 30.00 USD"
    }

    "formatSettleSuggestions says everyone's settled up when there's nothing to do" {
        formatSettleSuggestions(emptyList(), members, currency = "USD") shouldBe "Everyone's settled up — nothing to do!"
    }
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :telegram:test --tests "split.telegram.MessageFormattingSpec"`
Expected: FAIL — none of these functions exist yet.

- [ ] **Step 3: Write the implementation**

Create `telegram/src/main/kotlin/split/telegram/MessageFormatting.kt`:

```kotlin
package split.telegram

import split.core.DebtPayment
import split.core.Expense
import split.core.Member
import split.core.MemberId
import split.core.SplitType
import java.math.BigDecimal
import java.math.RoundingMode

fun formatAmount(amount: BigDecimal, currency: String): String =
    "${amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString()} $currency"

// Every message is sent with parse_mode HTML (see HttpTelegramApi), so any user-controlled
// text — display names, expense descriptions — must be escaped before being interpolated
// into a formatted string, or a stray '<', '>', or '&' produces malformed HTML that Telegram
// either mangles or rejects outright. Static text we write ourselves doesn't need this, but
// must in turn avoid raw '<'/'>' of its own (see HELP_TEXT and the Usage messages, which use
// <code>...</code> placeholders instead of bare <placeholder> for exactly this reason).
internal fun escapeHtml(text: String): String = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")

fun formatExpenseConfirmation(expense: Expense, members: List<Member>): String {
    val nameOf = members.associateBy { it.id }
    val payerName = escapeHtml(nameOf.getValue(expense.payerId).displayName)
    val base = "$payerName paid ${formatAmount(expense.amount, expense.currency)} for <b>${escapeHtml(expense.description)}</b>"

    // The payer is dropped from this list — they're already named as the payer, so
    // repeating them in "split with" reads as if they split the expense with themselves.
    // Sorted by name rather than left in expense.shares' order: that order reflects
    // incidental database row order on read (member_id happens to sort the rows), not
    // anything meaningful, so leaving it unsorted would show participants in a different,
    // effectively random order every time the same expense is displayed.
    val otherParticipants = expense.shares
        .filter { it.memberId != expense.payerId }
        .sortedBy { nameOf.getValue(it.memberId).displayName }
        .joinToString(", ") { escapeHtml(nameOf.getValue(it.memberId).displayName) }

    return if (otherParticipants.isEmpty()) {
        base
    } else {
        "$base, split ${splitTypeLabel(expense.splitType)} with $otherParticipants"
    }
}

private fun splitTypeLabel(splitType: SplitType): String = when (splitType) {
    SplitType.EQUAL -> "equally"
    SplitType.EXACT -> "by exact amounts"
    SplitType.SHARES -> "by shares"
}

fun formatExpenseList(expenses: List<Expense>, members: List<Member>): String {
    if (expenses.isEmpty()) return "No expenses yet — use /add to log one."
    // Blank line between entries, since a wall of unbroken lines is hard to scan in Telegram.
    // Header names the 10-expense cap ListCommand applies, so it's not a mystery why an
    // older expense might be missing from the list.
    return "<b>Last 10 expenses:</b>\n\n" + expenses.joinToString("\n\n") { formatExpenseListLine(it, members) }
}

// Deliberately its own format rather than reusing formatExpenseConfirmation: /list is an
// audit view, so unlike the brief /add confirmation it needs the date and, critically,
// each person's actual share amount — for an EQUAL split that's implied (everyone pays the
// same), but that's the whole point of EXACT/SHARES splits: amounts differ per person, and
// naming the split type without the breakdown wouldn't say how much anyone actually owes.
// Entries are joined with a blank line and each one spans two lines (headline, then the
// paid-by/breakdown line) — the HTML-formatting review round found the original single-line-
// per-expense, no-blank-line layout unreadable once there were more than a couple of expenses.
private fun formatExpenseListLine(expense: Expense, members: List<Member>): String {
    val nameOf = members.associateBy { it.id }
    val shortId = expense.id.value.take(8)
    val date = expense.createdAt.toString().take(10)
    val payerName = escapeHtml(nameOf.getValue(expense.payerId).displayName)
    val breakdown = expense.shares
        .sortedBy { nameOf.getValue(it.memberId).displayName }
        .joinToString(", ") { share ->
            "${escapeHtml(nameOf.getValue(share.memberId).displayName)} ${formatAmount(share.shareAmount, expense.currency)}"
        }

    return "<code>$shortId</code>  $date  <b>${escapeHtml(expense.description)}</b>  " +
        "${formatAmount(expense.amount, expense.currency)}\n" +
        "paid by $payerName, split ${splitTypeLabel(expense.splitType)}: $breakdown"
}

fun formatBalances(payments: List<DebtPayment>, members: List<Member>, viewerId: MemberId, currency: String): String {
    val nameOf = members.associateBy { it.id }
    val relevant = payments.filter { it.from == viewerId || it.to == viewerId }
    if (relevant.isEmpty()) return "You're all settled up!"

    return relevant.joinToString("\n") { payment ->
        val amount = formatAmount(payment.amount, currency)
        if (payment.from == viewerId) {
            "You owe ${escapeHtml(nameOf.getValue(payment.to).displayName)} $amount"
        } else {
            "${escapeHtml(nameOf.getValue(payment.from).displayName)} owes you $amount"
        }
    }
}

fun formatSettleSuggestions(payments: List<DebtPayment>, members: List<Member>, currency: String): String {
    if (payments.isEmpty()) return "Everyone's settled up — nothing to do!"
    val nameOf = members.associateBy { it.id }
    return payments.joinToString("\n") { payment ->
        val from = escapeHtml(nameOf.getValue(payment.from).displayName)
        val to = escapeHtml(nameOf.getValue(payment.to).displayName)
        "$from pays $to ${formatAmount(payment.amount, currency)}"
    }
}
```

Note: `nameOf.getValue(id)` (not `nameOf[id] ?: "someone"`) is deliberate — a payer or share/payment participant missing from the group's member list is a data-integrity bug upstream, not a display edge case, and should fail loudly (`NoSuchElementException`) rather than silently showing "someone" to users.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :telegram:test --tests "split.telegram.MessageFormattingSpec"`
Expected: `BUILD SUCCESSFUL`, 14 tests pass.

- [ ] **Step 5: Commit**

```bash
git add telegram/src/main/kotlin/split/telegram/MessageFormatting.kt \
        telegram/src/test/kotlin/split/telegram/MessageFormattingSpec.kt
git commit -m "Add message formatting for expenses, balances, and settle suggestions"
```

---

### Task 10: `/add` command (mention-based, equal split)

Mention-based only, per this plan's scope — `/add 90 dinner` with no mentions (the button picker) is deferred (see "Out of scope").

**Files:**
- Modify: `telegram/src/main/kotlin/split/telegram/CommandParsing.kt`
- Create: `telegram/src/main/kotlin/split/telegram/AddExpenseCommand.kt`
- Test: `telegram/src/test/kotlin/split/telegram/CommandParsingSpec.kt` (add `parseAddArgs` cases)
- Test: `telegram/src/test/kotlin/split/telegram/AddExpenseCommandSpec.kt`

**Interfaces:**
- Consumes: `PlatformDirectory`, `GroupRepository`, `MemberRepository`, `ExpenseRepository`, `resolveEqualSplit`, `Expense`, `ExpenseId`, `SplitType` (`core`); `IdentityResolver` (Task 4); `formatExpenseConfirmation` (Task 9); `extractMentions` (Task 5).
- Produces: `data class AddExpenseArgs(amount: BigDecimal, currency: String, description: String, mentionUsernames: List<String>)`, `fun parseAddArgs(args: String, defaultCurrency: String): AddExpenseArgs`, `class AddExpenseCommand(platformDirectory: PlatformDirectory, groupRepository: GroupRepository, memberRepository: MemberRepository, expenseRepository: ExpenseRepository, identityResolver: IdentityResolver, telegramApi: TelegramApi) { suspend fun handle(context: CommandContext) }`.

- [ ] **Step 1: Write the failing parsing tests**

Append to `telegram/src/test/kotlin/split/telegram/CommandParsingSpec.kt`, inside the `StringSpec({ ... })` block:

```kotlin
    "parseAddArgs with no currency uses the group default" {
        parseAddArgs("90 dinner @alice @bobby", defaultCurrency = "USD") shouldBe
            AddExpenseArgs(BigDecimal("90"), "USD", "dinner", listOf("alice", "bobby"))
    }

    "parseAddArgs with an explicit currency overrides the default" {
        parseAddArgs("90 EUR dinner @alice", defaultCurrency = "USD") shouldBe
            AddExpenseArgs(BigDecimal("90"), "EUR", "dinner", listOf("alice"))
    }

    "parseAddArgs keeps a multi-word description that isn't a currency code" {
        parseAddArgs("90 Fancy Dinner Party @alice", defaultCurrency = "USD") shouldBe
            AddExpenseArgs(BigDecimal("90"), "USD", "Fancy Dinner Party", listOf("alice"))
    }

    "parseAddArgs rejects no mentions" {
        shouldThrow<IllegalArgumentException> { parseAddArgs("90 dinner", defaultCurrency = "USD") }
    }
```

Add the two needed imports at the top of the file:

```kotlin
import io.kotest.assertions.throwables.shouldThrow
import java.math.BigDecimal
```

- [ ] **Step 2: Write the failing command test**

Create `telegram/src/test/kotlin/split/telegram/AddExpenseCommandSpec.kt`:

```kotlin
package split.telegram

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import split.storage.ExposedExpenseRepository
import split.storage.ExposedGroupRepository
import split.storage.ExposedMemberRepository
import split.storage.ExposedPlatformDirectory

class AddExpenseCommandSpec : StringSpec({

    "logs an equal-split expense between the sender and mentioned members" {
        withTestDatabase { db ->
            val platformDirectory = ExposedPlatformDirectory(db)
            val groupRepository = ExposedGroupRepository(db)
            val memberRepository = ExposedMemberRepository(db)
            val expenseRepository = ExposedExpenseRepository(db)
            val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)

            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val groupId = resolver.resolveGroup("-100001")
            resolver.ensureGroupMembership(groupId, aliceId)
            resolver.resolveMember("2", "bobby", "Bob") // Bob has run a command before, so @bobby resolves

            val telegramApi = FakeTelegramApi()
            val command = AddExpenseCommand(
                platformDirectory, groupRepository, memberRepository, expenseRepository, resolver, telegramApi,
            )

            command.handle(CommandContext(-100, aliceId, "1", groupId, "90 dinner @bobby"))

            val expenses = expenseRepository.listActive(groupId, "USD")
            expenses.single().amount shouldBe BigDecimal("90.00")
            expenses.single().shares.map { it.memberId.value }.toSet() shouldBe
                setOf(aliceId.value, memberRepository.findByGroup(groupId).first { it.displayName == "Bob" }.id.value)
            telegramApi.sentMessages.single().first shouldBe -100L
        }
    }

    "replies with an error and doesn't create an expense for an unrecognized mention" {
        withTestDatabase { db ->
            val platformDirectory = ExposedPlatformDirectory(db)
            val groupRepository = ExposedGroupRepository(db)
            val memberRepository = ExposedMemberRepository(db)
            val expenseRepository = ExposedExpenseRepository(db)
            val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)

            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val groupId = resolver.resolveGroup("-100001")

            val telegramApi = FakeTelegramApi()
            val command = AddExpenseCommand(
                platformDirectory, groupRepository, memberRepository, expenseRepository, resolver, telegramApi,
            )

            command.handle(CommandContext(-100, aliceId, "1", groupId, "90 dinner @stranger"))

            expenseRepository.listActive(groupId, "USD") shouldBe emptyList()
            telegramApi.sentMessages.single().second shouldBe
                "I don't recognize @stranger yet — ask them to run /start with me first."
        }
    }
})
```

Note: any `@mention` used as test data (not just illustrative message copy) must be 5+ characters after the `@` — `mentionPattern` (Task 5) requires `[a-zA-Z][a-zA-Z0-9_]{4,31}`, matching Telegram's real minimum username length. A 3-4 character placeholder like `@bob` silently fails to match, so `extractMentions` drops it — this surfaced as a real bug during implementation (`parseAddArgs` rejected the command as having zero mentions). Use names like `alice`, `bobby`, `carol` (5+ chars) in any test that round-trips through `extractMentions`/`parseAddArgs`/`parseSettleArgs`. This applies to Task 14's `parseSettleArgs`/`SettleCommandSpec` tests below too.

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew :telegram:test --tests "split.telegram.CommandParsingSpec" --tests "split.telegram.AddExpenseCommandSpec"`
Expected: FAIL — `parseAddArgs`, `AddExpenseArgs`, `AddExpenseCommand` don't exist yet.

- [ ] **Step 4: Add `parseAddArgs` to `CommandParsing.kt`**

Append to `telegram/src/main/kotlin/split/telegram/CommandParsing.kt`:

```kotlin
import java.math.BigDecimal

data class AddExpenseArgs(
    val amount: BigDecimal,
    val currency: String,
    val description: String,
    val mentionUsernames: List<String>,
)

private val currencyCodePattern = Regex("^[A-Z]{3}$")

fun parseAddArgs(args: String, defaultCurrency: String): AddExpenseArgs {
    val mentions = extractMentions(args)
    require(mentions.isNotEmpty()) { "Mention at least one participant, e.g. /add 90 dinner <code>@alice</code> <code>@bob</code>" }

    val withoutMentions = mentionPattern.replace(args, "").trim().replace(Regex("\\s+"), " ")
    val parts = withoutMentions.split(" ", limit = 2)
    require(parts.size == 2) { "Usage: /add <code>amount</code> [<code>currency</code>] <code>description</code> <code>@mentions...</code>" }

    val amount = BigDecimal(parts[0])
    val rest = parts[1]
    val restParts = rest.split(" ", limit = 2)

    return if (restParts.size == 2 && currencyCodePattern.matches(restParts[0])) {
        AddExpenseArgs(amount, restParts[0], restParts[1], mentions)
    } else {
        AddExpenseArgs(amount, defaultCurrency, rest, mentions)
    }
}
```

(The `import java.math.BigDecimal` line must be moved up next to the file's `package` line, not left inline — Kotlin requires imports before any declaration.)

- [ ] **Step 5: Write `AddExpenseCommand`**

Create `telegram/src/main/kotlin/split/telegram/AddExpenseCommand.kt`:

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
import java.time.Clock
import java.time.Instant
import java.util.UUID

class AddExpenseCommand(
    private val platformDirectory: PlatformDirectory,
    private val groupRepository: GroupRepository,
    private val memberRepository: MemberRepository,
    private val expenseRepository: ExpenseRepository,
    private val identityResolver: IdentityResolver,
    private val telegramApi: TelegramApi,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun handle(context: CommandContext) {
        val group = groupRepository.find(context.groupId) ?: error("Group ${context.groupId} not found")

        val parsed = try {
            parseAddArgs(context.args, group.defaultCurrency)
        } catch (e: IllegalArgumentException) {
            telegramApi.sendMessage(context.chatId, e.message ?: "Invalid /add usage")
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

        val shares = resolveEqualSplit(parsed.amount, context.memberId, uniqueParticipantIds)
        val expense = Expense(
            id = ExpenseId(idGenerator()),
            groupId = context.groupId,
            currency = parsed.currency,
            description = parsed.description,
            amount = parsed.amount,
            payerId = context.memberId,
            splitType = SplitType.EQUAL,
            createdBy = context.memberId,
            createdAt = Instant.now(clock),
            shares = shares,
        )
        expenseRepository.create(expense)

        val members = memberRepository.findByGroup(context.groupId)
        telegramApi.sendMessage(context.chatId, formatExpenseConfirmation(expense, members))
    }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew :telegram:test --tests "split.telegram.CommandParsingSpec" --tests "split.telegram.AddExpenseCommandSpec"`
Expected: `BUILD SUCCESSFUL`, all tests pass (10 in `CommandParsingSpec`, 2 in `AddExpenseCommandSpec`).

- [ ] **Step 7: Commit**

```bash
git add telegram/src/main/kotlin/split/telegram/CommandParsing.kt \
        telegram/src/main/kotlin/split/telegram/AddExpenseCommand.kt \
        telegram/src/test/kotlin/split/telegram/CommandParsingSpec.kt \
        telegram/src/test/kotlin/split/telegram/AddExpenseCommandSpec.kt
git commit -m "Add /add command (mention-based equal split)"
```

---

### Task 11: `/delete` command

Expense IDs are UUID strings, not short numbers — `/list` (Task 12) shows an 8-character prefix, and `/delete` matches by that prefix among the group's active expenses.

**Files:**
- Create: `telegram/src/main/kotlin/split/telegram/DeleteExpenseCommand.kt`
- Test: `telegram/src/test/kotlin/split/telegram/DeleteExpenseCommandSpec.kt`

**Interfaces:**
- Consumes: `GroupRepository`, `ExpenseRepository`, `canDeleteExpense` (`core`); `TelegramApi.getChatAdministrators` (Task 3); `CommandContext.externalUserId` (Task 6).
- Produces: `class DeleteExpenseCommand(groupRepository: GroupRepository, expenseRepository: ExpenseRepository, telegramApi: TelegramApi) { suspend fun handle(context: CommandContext) }`.

- [ ] **Step 1: Write the failing test**

Create `telegram/src/test/kotlin/split/telegram/DeleteExpenseCommandSpec.kt`:

```kotlin
package split.telegram

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant
import split.core.Expense
import split.core.ExpenseId
import split.core.ExpenseShare
import split.core.SplitType
import split.storage.ExposedExpenseRepository
import split.storage.ExposedGroupRepository
import split.storage.ExposedMemberRepository
import split.storage.ExposedPlatformDirectory

class DeleteExpenseCommandSpec : StringSpec({

    "the payer can delete their own expense" {
        withTestDatabase { db ->
            val groupRepository = ExposedGroupRepository(db)
            val expenseRepository = ExposedExpenseRepository(db)
            val resolver = IdentityResolver(ExposedPlatformDirectory(db), ExposedMemberRepository(db), groupRepository)
            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val groupId = resolver.resolveGroup("-100001")
            resolver.ensureGroupMembership(groupId, aliceId)

            val expense = Expense(
                id = ExpenseId("abcdef1234567890"),
                groupId = groupId,
                currency = "USD",
                description = "dinner",
                amount = BigDecimal("90.00"),
                payerId = aliceId,
                splitType = SplitType.EQUAL,
                createdBy = aliceId,
                createdAt = Instant.parse("2026-08-28T00:00:00Z"),
                shares = listOf(ExpenseShare(aliceId, BigDecimal("90.00"))),
            )
            expenseRepository.create(expense)

            val telegramApi = FakeTelegramApi()
            val command = DeleteExpenseCommand(groupRepository, expenseRepository, telegramApi)

            command.handle(CommandContext(-100, aliceId, "1", groupId, "abcdef12"))

            expenseRepository.listActive(groupId, "USD") shouldBe emptyList()
            telegramApi.sentMessages shouldBe listOf(-100L to "Deleted \"dinner\" (90.00 USD).")
        }
    }

    "a non-payer, non-admin cannot delete the expense" {
        withTestDatabase { db ->
            val groupRepository = ExposedGroupRepository(db)
            val expenseRepository = ExposedExpenseRepository(db)
            val resolver = IdentityResolver(ExposedPlatformDirectory(db), ExposedMemberRepository(db), groupRepository)
            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val bobId = resolver.resolveMember("2", "bob", "Bob")
            val groupId = resolver.resolveGroup("-100001")
            resolver.ensureGroupMembership(groupId, aliceId)
            resolver.ensureGroupMembership(groupId, bobId)

            val expense = Expense(
                id = ExpenseId("abcdef1234567890"),
                groupId = groupId,
                currency = "USD",
                description = "dinner",
                amount = BigDecimal("90.00"),
                payerId = aliceId,
                splitType = SplitType.EQUAL,
                createdBy = aliceId,
                createdAt = Instant.parse("2026-08-28T00:00:00Z"),
                shares = listOf(ExpenseShare(aliceId, BigDecimal("90.00"))),
            )
            expenseRepository.create(expense)

            val telegramApi = FakeTelegramApi()
            val command = DeleteExpenseCommand(groupRepository, expenseRepository, telegramApi)

            command.handle(CommandContext(-100, bobId, "2", groupId, "abcdef12"))

            expenseRepository.listActive(groupId, "USD").size shouldBe 1
            telegramApi.sentMessages shouldBe listOf(-100L to "Only the payer or a group admin can delete this expense.")
        }
    }

    "a group admin can delete someone else's expense" {
        withTestDatabase { db ->
            val groupRepository = ExposedGroupRepository(db)
            val expenseRepository = ExposedExpenseRepository(db)
            val resolver = IdentityResolver(ExposedPlatformDirectory(db), ExposedMemberRepository(db), groupRepository)
            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val bobId = resolver.resolveMember("2", "bob", "Bob")
            val groupId = resolver.resolveGroup("-100001")
            resolver.ensureGroupMembership(groupId, aliceId)
            resolver.ensureGroupMembership(groupId, bobId)

            val expense = Expense(
                id = ExpenseId("abcdef1234567890"),
                groupId = groupId,
                currency = "USD",
                description = "dinner",
                amount = BigDecimal("90.00"),
                payerId = aliceId,
                splitType = SplitType.EQUAL,
                createdBy = aliceId,
                createdAt = Instant.parse("2026-08-28T00:00:00Z"),
                shares = listOf(ExpenseShare(aliceId, BigDecimal("90.00"))),
            )
            expenseRepository.create(expense)

            val telegramApi = FakeTelegramApi()
            telegramApi.chatAdministrators = listOf(TgChatMember(status = "administrator", user = TgUser(id = 2, firstName = "Bob")))
            val command = DeleteExpenseCommand(groupRepository, expenseRepository, telegramApi)

            command.handle(CommandContext(-100, bobId, "2", groupId, "abcdef12"))

            expenseRepository.listActive(groupId, "USD") shouldBe emptyList()
        }
    }

    "replies when no expense matches the given id" {
        withTestDatabase { db ->
            val groupRepository = ExposedGroupRepository(db)
            val expenseRepository = ExposedExpenseRepository(db)
            val resolver = IdentityResolver(ExposedPlatformDirectory(db), ExposedMemberRepository(db), groupRepository)
            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val groupId = resolver.resolveGroup("-100001")

            val telegramApi = FakeTelegramApi()
            val command = DeleteExpenseCommand(groupRepository, expenseRepository, telegramApi)

            command.handle(CommandContext(-100, aliceId, "1", groupId, "zzzzzzzz"))

            telegramApi.sentMessages shouldBe listOf(-100L to "No active expense found matching \"zzzzzzzz\" — check /list.")
        }
    }
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :telegram:test --tests "split.telegram.DeleteExpenseCommandSpec"`
Expected: FAIL — `DeleteExpenseCommand` doesn't exist yet.

- [ ] **Step 3: Write the implementation**

Create `telegram/src/main/kotlin/split/telegram/DeleteExpenseCommand.kt`:

```kotlin
package split.telegram

import split.core.ExpenseRepository
import split.core.GroupRepository
import split.core.canDeleteExpense
import java.time.Clock
import java.time.Instant

class DeleteExpenseCommand(
    private val groupRepository: GroupRepository,
    private val expenseRepository: ExpenseRepository,
    private val telegramApi: TelegramApi,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun handle(context: CommandContext) {
        val idPrefix = context.args.trim()
        if (idPrefix.isEmpty()) {
            telegramApi.sendMessage(context.chatId, "Usage: /delete <code>id</code> (see /list for ids)")
            return
        }

        val group = groupRepository.find(context.groupId) ?: error("Group ${context.groupId} not found")
        val expense = expenseRepository.listActive(context.groupId, group.defaultCurrency)
            .firstOrNull { it.id.value.startsWith(idPrefix, ignoreCase = true) }

        if (expense == null) {
            telegramApi.sendMessage(context.chatId, "No active expense found matching \"${escapeHtml(idPrefix)}\" — check /list.")
            return
        }

        val admins = telegramApi.getChatAdministrators(context.chatId)
        val isAdmin = admins.any { it.user.id.toString() == context.externalUserId }

        if (!canDeleteExpense(expense, context.memberId, isAdmin)) {
            telegramApi.sendMessage(context.chatId, "Only the payer or a group admin can delete this expense.")
            return
        }

        expenseRepository.softDelete(expense.id, Instant.now(clock))
        telegramApi.sendMessage(
            context.chatId,
            "Deleted \"${escapeHtml(expense.description)}\" (${formatAmount(expense.amount, expense.currency)}).",
        )
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :telegram:test --tests "split.telegram.DeleteExpenseCommandSpec"`
Expected: `BUILD SUCCESSFUL`, 4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add telegram/src/main/kotlin/split/telegram/DeleteExpenseCommand.kt \
        telegram/src/test/kotlin/split/telegram/DeleteExpenseCommandSpec.kt
git commit -m "Add /delete command"
```

---

### Task 12: `/list` command

**Files:**
- Create: `telegram/src/main/kotlin/split/telegram/ListCommand.kt`
- Test: `telegram/src/test/kotlin/split/telegram/ListCommandSpec.kt`

**Interfaces:**
- Consumes: `GroupRepository`, `MemberRepository`, `ExpenseRepository` (`core`); `formatExpenseList` (Task 9).
- Produces: `class ListCommand(groupRepository: GroupRepository, memberRepository: MemberRepository, expenseRepository: ExpenseRepository, telegramApi: TelegramApi) { suspend fun handle(context: CommandContext) }`.

Note: `memberRepository` was added during review — manual testing showed a bare `[id] description — amount` line per expense wasn't useful without knowing who paid and who was in on it, so `ListCommand` now fetches the group's members and reuses `formatExpenseConfirmation`'s payer/participant formatting per line (see Task 9's updated `formatExpenseList` signature).

- [ ] **Step 1: Write the failing test**

Create `telegram/src/test/kotlin/split/telegram/ListCommandSpec.kt`:

```kotlin
package split.telegram

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant
import split.core.Expense
import split.core.ExpenseId
import split.core.ExpenseShare
import split.core.SplitType
import split.storage.ExposedExpenseRepository
import split.storage.ExposedGroupRepository
import split.storage.ExposedMemberRepository
import split.storage.ExposedPlatformDirectory

class ListCommandSpec : StringSpec({

    "lists active expenses newest first, capped at 10, with who paid and who participated" {
        withTestDatabase { db ->
            val groupRepository = ExposedGroupRepository(db)
            val memberRepository = ExposedMemberRepository(db)
            val expenseRepository = ExposedExpenseRepository(db)
            val resolver = IdentityResolver(ExposedPlatformDirectory(db), memberRepository, groupRepository)
            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val bobbyId = resolver.resolveMember("2", "bobby", "Bob")
            val groupId = resolver.resolveGroup("-100001")
            resolver.ensureGroupMembership(groupId, aliceId)
            resolver.ensureGroupMembership(groupId, bobbyId)

            // ids are unrelated to their descriptions on purpose, to make it obvious in the
            // expected output below which part is the 8-char id prefix vs. the description
            expenseRepository.create(
                Expense(
                    id = ExpenseId("older1234567890"),
                    groupId = groupId,
                    currency = "USD",
                    description = "lunch",
                    amount = BigDecimal("10.00"),
                    payerId = aliceId,
                    splitType = SplitType.EQUAL,
                    createdBy = aliceId,
                    createdAt = Instant.parse("2026-08-27T00:00:00Z"),
                    shares = listOf(ExpenseShare(aliceId, BigDecimal("10.00"))),
                ),
            )
            expenseRepository.create(
                Expense(
                    id = ExpenseId("newer1234567890"),
                    groupId = groupId,
                    currency = "USD",
                    description = "dinner",
                    amount = BigDecimal("20.00"),
                    payerId = bobbyId,
                    splitType = SplitType.EQUAL,
                    createdBy = bobbyId,
                    createdAt = Instant.parse("2026-08-28T00:00:00Z"),
                    shares = listOf(ExpenseShare(aliceId, BigDecimal("10.00")), ExpenseShare(bobbyId, BigDecimal("10.00"))),
                ),
            )

            val telegramApi = FakeTelegramApi()
            val command = ListCommand(groupRepository, memberRepository, expenseRepository, telegramApi)

            command.handle(CommandContext(-100, aliceId, "1", groupId, ""))

            telegramApi.sentMessages shouldBe listOf(
                -100L to "<b>Last 10 expenses:</b>\n\n" +
                    "<code>newer123</code>  2026-08-28  <b>dinner</b>  20.00 USD\n" +
                    "paid by Bob, split equally: Alice 10.00 USD, Bob 10.00 USD\n\n" +
                    "<code>older123</code>  2026-08-27  <b>lunch</b>  10.00 USD\n" +
                    "paid by Alice, split equally: Alice 10.00 USD",
            )
        }
    }

    "shows the real per-person breakdown for exact and shares splits, not just equal" {
        withTestDatabase { db ->
            val groupRepository = ExposedGroupRepository(db)
            val memberRepository = ExposedMemberRepository(db)
            val expenseRepository = ExposedExpenseRepository(db)
            val resolver = IdentityResolver(ExposedPlatformDirectory(db), memberRepository, groupRepository)
            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val bobbyId = resolver.resolveMember("2", "bobby", "Bob")
            val groupId = resolver.resolveGroup("-100001")
            resolver.ensureGroupMembership(groupId, aliceId)
            resolver.ensureGroupMembership(groupId, bobbyId)

            expenseRepository.create(
                Expense(
                    id = ExpenseId("aaaa1234567890"),
                    groupId = groupId,
                    currency = "USD",
                    description = "rent",
                    amount = BigDecimal("100.00"),
                    payerId = aliceId,
                    splitType = SplitType.EXACT,
                    createdBy = aliceId,
                    createdAt = Instant.parse("2026-08-28T00:00:00Z"),
                    shares = listOf(ExpenseShare(aliceId, BigDecimal("60.00")), ExpenseShare(bobbyId, BigDecimal("40.00"))),
                ),
            )
            expenseRepository.create(
                Expense(
                    id = ExpenseId("bbbb1234567890"),
                    groupId = groupId,
                    currency = "USD",
                    description = "utilities",
                    amount = BigDecimal("90.00"),
                    payerId = bobbyId,
                    splitType = SplitType.SHARES,
                    createdBy = bobbyId,
                    createdAt = Instant.parse("2026-08-29T00:00:00Z"),
                    shares = listOf(ExpenseShare(aliceId, BigDecimal("30.00")), ExpenseShare(bobbyId, BigDecimal("60.00"))),
                ),
            )

            val telegramApi = FakeTelegramApi()
            val command = ListCommand(groupRepository, memberRepository, expenseRepository, telegramApi)

            command.handle(CommandContext(-100, aliceId, "1", groupId, ""))

            telegramApi.sentMessages shouldBe listOf(
                -100L to "<b>Last 10 expenses:</b>\n\n" +
                    "<code>bbbb1234</code>  2026-08-29  <b>utilities</b>  90.00 USD\n" +
                    "paid by Bob, split by shares: Alice 30.00 USD, Bob 60.00 USD\n\n" +
                    "<code>aaaa1234</code>  2026-08-28  <b>rent</b>  100.00 USD\n" +
                    "paid by Alice, split by exact amounts: Alice 60.00 USD, Bob 40.00 USD",
            )
        }
    }

    "explains there's nothing yet" {
        withTestDatabase { db ->
            val groupRepository = ExposedGroupRepository(db)
            val memberRepository = ExposedMemberRepository(db)
            val expenseRepository = ExposedExpenseRepository(db)
            val resolver = IdentityResolver(ExposedPlatformDirectory(db), memberRepository, groupRepository)
            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val groupId = resolver.resolveGroup("-100001")

            val telegramApi = FakeTelegramApi()
            val command = ListCommand(groupRepository, memberRepository, expenseRepository, telegramApi)

            command.handle(CommandContext(-100, aliceId, "1", groupId, ""))

            telegramApi.sentMessages shouldBe listOf(-100L to "No expenses yet — use /add to log one.")
        }
    }
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :telegram:test --tests "split.telegram.ListCommandSpec"`
Expected: FAIL — `ListCommand` doesn't exist yet.

- [ ] **Step 3: Write the implementation**

Create `telegram/src/main/kotlin/split/telegram/ListCommand.kt`:

```kotlin
package split.telegram

import split.core.ExpenseRepository
import split.core.GroupRepository
import split.core.MemberRepository

class ListCommand(
    private val groupRepository: GroupRepository,
    private val memberRepository: MemberRepository,
    private val expenseRepository: ExpenseRepository,
    private val telegramApi: TelegramApi,
) {
    suspend fun handle(context: CommandContext) {
        val group = groupRepository.find(context.groupId) ?: error("Group ${context.groupId} not found")
        val expenses = expenseRepository.listActive(context.groupId, group.defaultCurrency)
            .sortedByDescending { it.createdAt }
            .take(10)
        val members = memberRepository.findByGroup(context.groupId)

        telegramApi.sendMessage(context.chatId, formatExpenseList(expenses, members))
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :telegram:test --tests "split.telegram.ListCommandSpec"`
Expected: `BUILD SUCCESSFUL`, 3 tests pass.

- [ ] **Step 5: Commit**

```bash
git add telegram/src/main/kotlin/split/telegram/ListCommand.kt \
        telegram/src/test/kotlin/split/telegram/ListCommandSpec.kt
git commit -m "Add /list command"
```

---

### Task 13: `/balances` command

**Files:**
- Create: `telegram/src/main/kotlin/split/telegram/BalancesCommand.kt`
- Test: `telegram/src/test/kotlin/split/telegram/BalancesCommandSpec.kt`

**Interfaces:**
- Consumes: `GroupRepository`, `MemberRepository`, `ExpenseRepository`, `SettlementRepository`, `computeBalances`, `simplifyDebts` (`core`); `formatBalances` (Task 9).
- Produces: `class BalancesCommand(groupRepository: GroupRepository, memberRepository: MemberRepository, expenseRepository: ExpenseRepository, settlementRepository: SettlementRepository, telegramApi: TelegramApi) { suspend fun handle(context: CommandContext) }`.

- [ ] **Step 1: Write the failing test**

Create `telegram/src/test/kotlin/split/telegram/BalancesCommandSpec.kt`:

```kotlin
package split.telegram

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant
import split.core.Expense
import split.core.ExpenseId
import split.core.ExpenseShare
import split.core.SplitType
import split.storage.ExposedExpenseRepository
import split.storage.ExposedGroupRepository
import split.storage.ExposedMemberRepository
import split.storage.ExposedPlatformDirectory
import split.storage.ExposedSettlementRepository

class BalancesCommandSpec : StringSpec({

    "shows what the viewer owes and is owed" {
        withTestDatabase { db ->
            val groupRepository = ExposedGroupRepository(db)
            val memberRepository = ExposedMemberRepository(db)
            val expenseRepository = ExposedExpenseRepository(db)
            val settlementRepository = ExposedSettlementRepository(db)
            val resolver = IdentityResolver(ExposedPlatformDirectory(db), memberRepository, groupRepository)

            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val bobId = resolver.resolveMember("2", "bob", "Bob")
            val groupId = resolver.resolveGroup("-100001")
            resolver.ensureGroupMembership(groupId, aliceId)
            resolver.ensureGroupMembership(groupId, bobId)

            expenseRepository.create(
                Expense(
                    id = ExpenseId("e1"),
                    groupId = groupId,
                    currency = "USD",
                    description = "dinner",
                    amount = BigDecimal("60.00"),
                    payerId = aliceId,
                    splitType = SplitType.EQUAL,
                    createdBy = aliceId,
                    createdAt = Instant.parse("2026-08-28T00:00:00Z"),
                    shares = listOf(ExpenseShare(aliceId, BigDecimal("30.00")), ExpenseShare(bobId, BigDecimal("30.00"))),
                ),
            )

            val telegramApi = FakeTelegramApi()
            val command = BalancesCommand(groupRepository, memberRepository, expenseRepository, settlementRepository, telegramApi)

            command.handle(CommandContext(-100, bobId, "2", groupId, ""))

            telegramApi.sentMessages shouldBe listOf(-100L to "You owe Alice 30.00 USD")
        }
    }

    "says everyone's settled up when there are no expenses" {
        withTestDatabase { db ->
            val groupRepository = ExposedGroupRepository(db)
            val memberRepository = ExposedMemberRepository(db)
            val expenseRepository = ExposedExpenseRepository(db)
            val settlementRepository = ExposedSettlementRepository(db)
            val resolver = IdentityResolver(ExposedPlatformDirectory(db), memberRepository, groupRepository)
            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val groupId = resolver.resolveGroup("-100001")

            val telegramApi = FakeTelegramApi()
            val command = BalancesCommand(groupRepository, memberRepository, expenseRepository, settlementRepository, telegramApi)

            command.handle(CommandContext(-100, aliceId, "1", groupId, ""))

            telegramApi.sentMessages shouldBe listOf(-100L to "You're all settled up!")
        }
    }
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :telegram:test --tests "split.telegram.BalancesCommandSpec"`
Expected: FAIL — `BalancesCommand` doesn't exist yet.

- [ ] **Step 3: Write the implementation**

Create `telegram/src/main/kotlin/split/telegram/BalancesCommand.kt`:

```kotlin
package split.telegram

import split.core.ExpenseRepository
import split.core.GroupRepository
import split.core.MemberRepository
import split.core.SettlementRepository
import split.core.computeBalances
import split.core.simplifyDebts

class BalancesCommand(
    private val groupRepository: GroupRepository,
    private val memberRepository: MemberRepository,
    private val expenseRepository: ExpenseRepository,
    private val settlementRepository: SettlementRepository,
    private val telegramApi: TelegramApi,
) {
    suspend fun handle(context: CommandContext) {
        val group = groupRepository.find(context.groupId) ?: error("Group ${context.groupId} not found")
        val expenses = expenseRepository.listActive(context.groupId, group.defaultCurrency)
        val settlements = settlementRepository.listActive(context.groupId, group.defaultCurrency)
        val balances = computeBalances(group.defaultCurrency, expenses, settlements)
        val payments = simplifyDebts(balances)
        val members = memberRepository.findByGroup(context.groupId)

        telegramApi.sendMessage(
            context.chatId,
            formatBalances(payments, members, context.memberId, group.defaultCurrency),
        )
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :telegram:test --tests "split.telegram.BalancesCommandSpec"`
Expected: `BUILD SUCCESSFUL`, 2 tests pass.

- [ ] **Step 5: Commit**

```bash
git add telegram/src/main/kotlin/split/telegram/BalancesCommand.kt \
        telegram/src/test/kotlin/split/telegram/BalancesCommandSpec.kt
git commit -m "Add /balances command"
```

---

### Task 14: `/settle` command

**Files:**
- Modify: `telegram/src/main/kotlin/split/telegram/CommandParsing.kt`
- Create: `telegram/src/main/kotlin/split/telegram/SettleCommand.kt`
- Test: `telegram/src/test/kotlin/split/telegram/CommandParsingSpec.kt` (add `parseSettleArgs` cases)
- Test: `telegram/src/test/kotlin/split/telegram/SettleCommandSpec.kt`

**Interfaces:**
- Consumes: `PlatformDirectory`, `GroupRepository`, `SettlementRepository`, `createSettlement` (`core`); `extractMentions` (Task 5).
- Produces: `data class SettleArgs(counterpartyUsername: String, amount: BigDecimal)`, `fun parseSettleArgs(args: String): SettleArgs`, `class SettleCommand(platformDirectory: PlatformDirectory, groupRepository: GroupRepository, settlementRepository: SettlementRepository, telegramApi: TelegramApi) { suspend fun handle(context: CommandContext) }`.

- [ ] **Step 1: Write the failing parsing tests**

Append to `telegram/src/test/kotlin/split/telegram/CommandParsingSpec.kt`, inside the `StringSpec({ ... })` block:

```kotlin
    "parseSettleArgs parses a mention and an amount" {
        parseSettleArgs("@bobby 20") shouldBe SettleArgs("bobby", BigDecimal("20"))
    }

    "parseSettleArgs rejects zero mentions" {
        shouldThrow<IllegalArgumentException> { parseSettleArgs("20") }
    }

    "parseSettleArgs rejects more than one mention" {
        shouldThrow<IllegalArgumentException> { parseSettleArgs("@bobby @carol 20") }
    }
```

- [ ] **Step 2: Write the failing command test**

Create `telegram/src/test/kotlin/split/telegram/SettleCommandSpec.kt`:

```kotlin
package split.telegram

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import split.storage.ExposedGroupRepository
import split.storage.ExposedMemberRepository
import split.storage.ExposedPlatformDirectory
import split.storage.ExposedSettlementRepository

class SettleCommandSpec : StringSpec({

    "records a settlement between the sender and the mentioned member" {
        withTestDatabase { db ->
            val platformDirectory = ExposedPlatformDirectory(db)
            val groupRepository = ExposedGroupRepository(db)
            val settlementRepository = ExposedSettlementRepository(db)
            val resolver = IdentityResolver(platformDirectory, ExposedMemberRepository(db), groupRepository)
            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            resolver.resolveMember("2", "bobby", "Bob")
            val groupId = resolver.resolveGroup("-100001")

            val telegramApi = FakeTelegramApi()
            val command = SettleCommand(platformDirectory, groupRepository, settlementRepository, telegramApi)

            command.handle(CommandContext(-100, aliceId, "1", groupId, "@bobby 20"))

            val settlement = settlementRepository.listActive(groupId, "USD").single()
            settlement.fromMemberId shouldBe aliceId
            settlement.amount shouldBe BigDecimal("20.00")
            telegramApi.sentMessages.single().first shouldBe -100L
        }
    }

    "records the settlement under the group's currency, not a hardcoded one" {
        withTestDatabase { db ->
            val platformDirectory = ExposedPlatformDirectory(db)
            val groupRepository = ExposedGroupRepository(db)
            val settlementRepository = ExposedSettlementRepository(db)
            val resolver = IdentityResolver(platformDirectory, ExposedMemberRepository(db), groupRepository)
            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            resolver.resolveMember("2", "bobby", "Bob")
            val groupId = resolver.resolveGroup("-100001")
            groupRepository.updateCurrency(groupId, "EUR")

            val telegramApi = FakeTelegramApi()
            val command = SettleCommand(platformDirectory, groupRepository, settlementRepository, telegramApi)

            command.handle(CommandContext(-100, aliceId, "1", groupId, "@bobby 20"))

            // if this were hardcoded to USD, listActive(groupId, "EUR") would come back empty
            // even though a settlement was created — exactly the bug this test guards against.
            val settlement = settlementRepository.listActive(groupId, "EUR").single()
            settlement.currency shouldBe "EUR"
            telegramApi.sentMessages.single().second shouldBe "Recorded: you paid 20.00 EUR."
        }
    }

    "replies when the mentioned person isn't recognized" {
        withTestDatabase { db ->
            val platformDirectory = ExposedPlatformDirectory(db)
            val groupRepository = ExposedGroupRepository(db)
            val settlementRepository = ExposedSettlementRepository(db)
            val resolver = IdentityResolver(platformDirectory, ExposedMemberRepository(db), groupRepository)
            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val groupId = resolver.resolveGroup("-100001")

            val telegramApi = FakeTelegramApi()
            val command = SettleCommand(platformDirectory, groupRepository, settlementRepository, telegramApi)

            command.handle(CommandContext(-100, aliceId, "1", groupId, "@stranger 20"))

            settlementRepository.listActive(groupId, "USD") shouldBe emptyList()
            telegramApi.sentMessages.single().second shouldBe
                "I don't recognize @stranger yet — ask them to run /start with me first."
        }
    }
})
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew :telegram:test --tests "split.telegram.CommandParsingSpec" --tests "split.telegram.SettleCommandSpec"`
Expected: FAIL — `parseSettleArgs`, `SettleArgs`, `SettleCommand` don't exist yet.

- [ ] **Step 4: Add `parseSettleArgs` to `CommandParsing.kt`**

Append to `telegram/src/main/kotlin/split/telegram/CommandParsing.kt`:

```kotlin
data class SettleArgs(val counterpartyUsername: String, val amount: BigDecimal)

fun parseSettleArgs(args: String): SettleArgs {
    val mentions = extractMentions(args)
    require(mentions.size == 1) { "Usage: /settle <code>@person</code> <code>amount</code>" }
    val withoutMention = mentionPattern.replace(args, "").trim()
    require(withoutMention.isNotEmpty()) { "Usage: /settle <code>@person</code> <code>amount</code>" }
    return SettleArgs(mentions[0], BigDecimal(withoutMention))
}
```

- [ ] **Step 5: Write `SettleCommand`**

Create `telegram/src/main/kotlin/split/telegram/SettleCommand.kt`:

```kotlin
package split.telegram

import split.core.GroupRepository
import split.core.PlatformDirectory
import split.core.SettlementId
import split.core.SettlementRepository
import split.core.createSettlement
import java.time.Clock
import java.time.Instant
import java.util.UUID

class SettleCommand(
    private val platformDirectory: PlatformDirectory,
    private val groupRepository: GroupRepository,
    private val settlementRepository: SettlementRepository,
    private val telegramApi: TelegramApi,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun handle(context: CommandContext) {
        val parsed = try {
            parseSettleArgs(context.args)
        } catch (e: IllegalArgumentException) {
            telegramApi.sendMessage(context.chatId, e.message ?: "Invalid /settle usage")
            return
        }

        val counterpartyId = platformDirectory.findMemberByUsername(IdentityResolver.PLATFORM, parsed.counterpartyUsername)
        if (counterpartyId == null) {
            telegramApi.sendMessage(
                context.chatId,
                "I don't recognize @${parsed.counterpartyUsername} yet — ask them to run /start with me first.",
            )
            return
        }

        val group = groupRepository.find(context.groupId) ?: error("Group ${context.groupId} not found")

        val settlement = try {
            createSettlement(
                id = SettlementId(idGenerator()),
                groupId = context.groupId,
                currency = group.defaultCurrency,
                from = context.memberId,
                to = counterpartyId,
                amount = parsed.amount,
                createdBy = context.memberId,
                createdAt = Instant.now(clock),
            )
        } catch (e: IllegalArgumentException) {
            telegramApi.sendMessage(context.chatId, e.message ?: "Invalid /settle amount")
            return
        }

        settlementRepository.create(settlement)
        telegramApi.sendMessage(context.chatId, "Recorded: you paid ${formatAmount(settlement.amount, settlement.currency)}.")
    }
}
```

Note: this uses `group.defaultCurrency`, matching every other money-touching command (`/add`, `/balances`, `/list`, `/delete`) — an earlier draft of this task hardcoded `currency = "USD"`, which would have silently recorded settlements under the wrong currency in any group that ran `/currency`, making them invisible to `/balances` (which filters by the group's actual currency). Caught and fixed during review, with a regression test (`"records the settlement under the group's currency, not a hardcoded one"`) proving it.

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew :telegram:test --tests "split.telegram.CommandParsingSpec" --tests "split.telegram.SettleCommandSpec"`
Expected: `BUILD SUCCESSFUL`, all tests pass (13 in `CommandParsingSpec`, 3 in `SettleCommandSpec`).

- [ ] **Step 7: Commit**

```bash
git add telegram/src/main/kotlin/split/telegram/CommandParsing.kt \
        telegram/src/main/kotlin/split/telegram/SettleCommand.kt \
        telegram/src/test/kotlin/split/telegram/CommandParsingSpec.kt \
        telegram/src/test/kotlin/split/telegram/SettleCommandSpec.kt
git commit -m "Add /settle command"
```

---

### Task 15: `/settle_suggest` command

**Files:**
- Create: `telegram/src/main/kotlin/split/telegram/SettleSuggestCommand.kt`
- Test: `telegram/src/test/kotlin/split/telegram/SettleSuggestCommandSpec.kt`

**Interfaces:**
- Consumes: `GroupRepository`, `MemberRepository`, `ExpenseRepository`, `SettlementRepository`, `computeBalances`, `simplifyDebts` (`core`); `formatSettleSuggestions` (Task 9).
- Produces: `class SettleSuggestCommand(groupRepository: GroupRepository, memberRepository: MemberRepository, expenseRepository: ExpenseRepository, settlementRepository: SettlementRepository, telegramApi: TelegramApi) { suspend fun handle(context: CommandContext) }`.

- [ ] **Step 1: Write the failing test**

Create `telegram/src/test/kotlin/split/telegram/SettleSuggestCommandSpec.kt`:

```kotlin
package split.telegram

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant
import split.core.Expense
import split.core.ExpenseId
import split.core.ExpenseShare
import split.core.SplitType
import split.storage.ExposedExpenseRepository
import split.storage.ExposedGroupRepository
import split.storage.ExposedMemberRepository
import split.storage.ExposedPlatformDirectory
import split.storage.ExposedSettlementRepository

class SettleSuggestCommandSpec : StringSpec({

    "suggests the minimal set of payments to settle the group" {
        withTestDatabase { db ->
            val groupRepository = ExposedGroupRepository(db)
            val memberRepository = ExposedMemberRepository(db)
            val expenseRepository = ExposedExpenseRepository(db)
            val settlementRepository = ExposedSettlementRepository(db)
            val resolver = IdentityResolver(ExposedPlatformDirectory(db), memberRepository, groupRepository)

            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val bobId = resolver.resolveMember("2", "bob", "Bob")
            val groupId = resolver.resolveGroup("-100001")
            resolver.ensureGroupMembership(groupId, aliceId)
            resolver.ensureGroupMembership(groupId, bobId)

            expenseRepository.create(
                Expense(
                    id = ExpenseId("e1"),
                    groupId = groupId,
                    currency = "USD",
                    description = "dinner",
                    amount = BigDecimal("60.00"),
                    payerId = aliceId,
                    splitType = SplitType.EQUAL,
                    createdBy = aliceId,
                    createdAt = Instant.parse("2026-08-28T00:00:00Z"),
                    shares = listOf(ExpenseShare(aliceId, BigDecimal("30.00")), ExpenseShare(bobId, BigDecimal("30.00"))),
                ),
            )

            val telegramApi = FakeTelegramApi()
            val command = SettleSuggestCommand(groupRepository, memberRepository, expenseRepository, settlementRepository, telegramApi)

            command.handle(CommandContext(-100, aliceId, "1", groupId, ""))

            telegramApi.sentMessages shouldBe listOf(-100L to "Bob pays Alice 30.00 USD")
        }
    }

    "says everyone's settled up when there's nothing to do" {
        withTestDatabase { db ->
            val groupRepository = ExposedGroupRepository(db)
            val memberRepository = ExposedMemberRepository(db)
            val expenseRepository = ExposedExpenseRepository(db)
            val settlementRepository = ExposedSettlementRepository(db)
            val resolver = IdentityResolver(ExposedPlatformDirectory(db), memberRepository, groupRepository)
            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val groupId = resolver.resolveGroup("-100001")

            val telegramApi = FakeTelegramApi()
            val command = SettleSuggestCommand(groupRepository, memberRepository, expenseRepository, settlementRepository, telegramApi)

            command.handle(CommandContext(-100, aliceId, "1", groupId, ""))

            telegramApi.sentMessages shouldBe listOf(-100L to "Everyone's settled up — nothing to do!")
        }
    }

    "simplifies crisscrossing debts across three people into the full minimal payment set" {
        withTestDatabase { db ->
            val groupRepository = ExposedGroupRepository(db)
            val memberRepository = ExposedMemberRepository(db)
            val expenseRepository = ExposedExpenseRepository(db)
            val settlementRepository = ExposedSettlementRepository(db)
            val resolver = IdentityResolver(ExposedPlatformDirectory(db), memberRepository, groupRepository)

            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val bobId = resolver.resolveMember("2", "bob", "Bob")
            val carolId = resolver.resolveMember("3", "carol", "Carol")
            val groupId = resolver.resolveGroup("-100001")
            resolver.ensureGroupMembership(groupId, aliceId)
            resolver.ensureGroupMembership(groupId, bobId)
            resolver.ensureGroupMembership(groupId, carolId)

            // Alice pays $30 for dinner, split three ways: Bob and Carol each owe Alice $10.
            expenseRepository.create(
                Expense(
                    id = ExpenseId("e1"),
                    groupId = groupId,
                    currency = "USD",
                    description = "dinner",
                    amount = BigDecimal("30.00"),
                    payerId = aliceId,
                    splitType = SplitType.EQUAL,
                    createdBy = aliceId,
                    createdAt = Instant.parse("2026-08-28T00:00:00Z"),
                    shares = listOf(
                        ExpenseShare(aliceId, BigDecimal("10.00")),
                        ExpenseShare(bobId, BigDecimal("10.00")),
                        ExpenseShare(carolId, BigDecimal("10.00")),
                    ),
                ),
            )
            // Bob pays $30 for drinks, split three ways: Alice and Carol each owe Bob $10.
            // Cancels the Alice<->Bob $10 IOU entirely — four raw obligations across the two
            // expenses collapse to two net payments, both from Carol.
            expenseRepository.create(
                Expense(
                    id = ExpenseId("e2"),
                    groupId = groupId,
                    currency = "USD",
                    description = "drinks",
                    amount = BigDecimal("30.00"),
                    payerId = bobId,
                    splitType = SplitType.EQUAL,
                    createdBy = bobId,
                    createdAt = Instant.parse("2026-08-29T00:00:00Z"),
                    shares = listOf(
                        ExpenseShare(aliceId, BigDecimal("10.00")),
                        ExpenseShare(bobId, BigDecimal("10.00")),
                        ExpenseShare(carolId, BigDecimal("10.00")),
                    ),
                ),
            )

            val telegramApi = FakeTelegramApi()
            val command = SettleSuggestCommand(groupRepository, memberRepository, expenseRepository, settlementRepository, telegramApi)

            command.handle(CommandContext(-100, aliceId, "1", groupId, ""))

            telegramApi.sentMessages shouldBe listOf(
                -100L to "Carol pays Alice 10.00 USD\nCarol pays Bob 10.00 USD",
            )
        }
    }
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :telegram:test --tests "split.telegram.SettleSuggestCommandSpec"`
Expected: FAIL — `SettleSuggestCommand` doesn't exist yet.

- [ ] **Step 3: Write the implementation**

Create `telegram/src/main/kotlin/split/telegram/SettleSuggestCommand.kt`:

```kotlin
package split.telegram

import split.core.ExpenseRepository
import split.core.GroupRepository
import split.core.MemberRepository
import split.core.SettlementRepository
import split.core.computeBalances
import split.core.simplifyDebts

class SettleSuggestCommand(
    private val groupRepository: GroupRepository,
    private val memberRepository: MemberRepository,
    private val expenseRepository: ExpenseRepository,
    private val settlementRepository: SettlementRepository,
    private val telegramApi: TelegramApi,
) {
    suspend fun handle(context: CommandContext) {
        val group = groupRepository.find(context.groupId) ?: error("Group ${context.groupId} not found")
        val expenses = expenseRepository.listActive(context.groupId, group.defaultCurrency)
        val settlements = settlementRepository.listActive(context.groupId, group.defaultCurrency)
        val balances = computeBalances(group.defaultCurrency, expenses, settlements)
        val payments = simplifyDebts(balances)
        val members = memberRepository.findByGroup(context.groupId)

        telegramApi.sendMessage(
            context.chatId,
            formatSettleSuggestions(payments, members, group.defaultCurrency),
        )
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :telegram:test --tests "split.telegram.SettleSuggestCommandSpec"`
Expected: `BUILD SUCCESSFUL`, 3 tests pass.

- [ ] **Step 5: Commit**

```bash
git add telegram/src/main/kotlin/split/telegram/SettleSuggestCommand.kt \
        telegram/src/test/kotlin/split/telegram/SettleSuggestCommandSpec.kt
git commit -m "Add /settle_suggest command"
```

---

### Task 16: Poll loop and application bootstrap

The composition root. `PollLoop`'s offset/dispatch logic is unit tested with `FakeTelegramApi`; `main()` itself is thin wiring, verified by manually running the bot (documented in Step 6) rather than an automated test.

**Files:**
- Create: `telegram/src/main/kotlin/split/telegram/BotApplication.kt`
- Test: `telegram/src/test/kotlin/split/telegram/PollLoopSpec.kt`

**Interfaces:**
- Consumes: `TelegramApi` (Task 3), `CommandRouter` (Task 6), every `*Command` class (Tasks 6–15), `IdentityResolver` (Task 4), `connectDatabaseFromEnv`, `ExposedMemberRepository`, `ExposedGroupRepository`, `ExposedExpenseRepository`, `ExposedSettlementRepository`, `ExposedPlatformDirectory` (`storage`).
- Produces: `class PollLoop(telegramApi: TelegramApi, router: CommandRouter) { suspend fun pollOnce(timeoutSeconds: Int = 30) }`, `suspend fun main()`.

- [ ] **Step 1: Write the failing test**

Create `telegram/src/test/kotlin/split/telegram/PollLoopSpec.kt`:

```kotlin
package split.telegram

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import split.storage.ExposedGroupRepository
import split.storage.ExposedMemberRepository
import split.storage.ExposedPlatformDirectory

class PollLoopSpec : StringSpec({

    fun anUpdate(id: Long, text: String) = TgUpdate(
        updateId = id,
        message = TgMessage(
            messageId = id,
            from = TgUser(id = 1, firstName = "Alice"),
            chat = TgChat(id = -1, type = "group"),
            text = text,
        ),
    )

    "dispatches every update returned and advances the offset past the last one" {
        withTestDatabase { db ->
            val resolver = IdentityResolver(ExposedPlatformDirectory(db), ExposedMemberRepository(db), ExposedGroupRepository(db))
            val telegramApi = FakeTelegramApi()
            val router = CommandRouter(resolver, mapOf("help" to HelpCommand(telegramApi)::handle))
            val pollLoop = PollLoop(telegramApi, router)

            telegramApi.updatesToReturn = listOf(anUpdate(5, "/help"), anUpdate(6, "/help"))
            pollLoop.pollOnce()

            telegramApi.sentMessages.size shouldBe 2

            telegramApi.updatesToReturn = emptyList()
            pollLoop.pollOnce()
            // offset now excludes update 5/6 — verified indirectly: a second identical
            // batch wouldn't be requested again by a real server, but pollOnce doesn't
            // re-request past updates on its own, so this just confirms no crash on empty.
            telegramApi.sentMessages.size shouldBe 2
        }
    }

    "one handler's exception doesn't stop the rest of the batch from being processed" {
        withTestDatabase { db ->
            val resolver = IdentityResolver(ExposedPlatformDirectory(db), ExposedMemberRepository(db), ExposedGroupRepository(db))
            val telegramApi = FakeTelegramApi()
            val router = CommandRouter(
                resolver,
                mapOf(
                    "boom" to { _: CommandContext -> throw RuntimeException("simulated failure") },
                    "help" to HelpCommand(telegramApi)::handle,
                ),
            )
            val pollLoop = PollLoop(telegramApi, router)

            telegramApi.updatesToReturn = listOf(anUpdate(1, "/boom"), anUpdate(2, "/help"))
            pollLoop.pollOnce()

            telegramApi.sentMessages shouldBe listOf(-1L to HELP_TEXT)
        }
    }
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :telegram:test --tests "split.telegram.PollLoopSpec"`
Expected: FAIL — `PollLoop` doesn't exist yet.

- [ ] **Step 3: Write `PollLoop` and `main()`**

Create `telegram/src/main/kotlin/split/telegram/BotApplication.kt`:

```kotlin
package split.telegram

import split.storage.ExposedExpenseRepository
import split.storage.ExposedGroupRepository
import split.storage.ExposedMemberRepository
import split.storage.ExposedPlatformDirectory
import split.storage.ExposedSettlementRepository
import split.storage.connectDatabaseFromEnv

class PollLoop(
    private val telegramApi: TelegramApi,
    private val router: CommandRouter,
) {
    private var offset: Long? = null

    suspend fun pollOnce(timeoutSeconds: Int = 30) {
        val updates = telegramApi.getUpdates(offset, timeoutSeconds)
        for (update in updates) {
            try {
                router.handleUpdate(update)
            } catch (e: Exception) {
                System.err.println("Error handling update ${update.updateId}: ${e.message}")
            }
            offset = update.updateId + 1
        }
    }
}

suspend fun main() {
    val botToken = System.getenv("TELEGRAM_BOT_TOKEN")
        ?: error("TELEGRAM_BOT_TOKEN environment variable is required")

    val db = connectDatabaseFromEnv()
    val memberRepository = ExposedMemberRepository(db)
    val groupRepository = ExposedGroupRepository(db)
    val expenseRepository = ExposedExpenseRepository(db)
    val settlementRepository = ExposedSettlementRepository(db)
    val platformDirectory = ExposedPlatformDirectory(db)

    val telegramApi = HttpTelegramApi(botToken)
    val identityResolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)

    val handlers = mapOf(
        "start" to StartCommand(telegramApi)::handle,
        "help" to HelpCommand(telegramApi)::handle,
        "currency" to CurrencyCommand(groupRepository, telegramApi)::handle,
        "members" to MembersCommand(memberRepository, telegramApi)::handle,
        "add" to AddExpenseCommand(
            platformDirectory, groupRepository, memberRepository, expenseRepository, identityResolver, telegramApi,
        )::handle,
        "delete" to DeleteExpenseCommand(groupRepository, expenseRepository, telegramApi)::handle,
        "list" to ListCommand(groupRepository, memberRepository, expenseRepository, telegramApi)::handle,
        "balances" to BalancesCommand(
            groupRepository, memberRepository, expenseRepository, settlementRepository, telegramApi,
        )::handle,
        "settle" to SettleCommand(platformDirectory, groupRepository, settlementRepository, telegramApi)::handle,
        "settle_suggest" to SettleSuggestCommand(
            groupRepository, memberRepository, expenseRepository, settlementRepository, telegramApi,
        )::handle,
    )

    val router = CommandRouter(identityResolver, handlers)
    val pollLoop = PollLoop(telegramApi, router)

    println("Bot started, polling for updates...")
    while (true) {
        pollLoop.pollOnce()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :telegram:test --tests "split.telegram.PollLoopSpec"`
Expected: `BUILD SUCCESSFUL`, 2 tests pass.

- [ ] **Step 5: Run the full test suite**

Run: `./gradlew test`
Expected: `BUILD SUCCESSFUL` across `:core`, `:storage`, and `:telegram`.

- [ ] **Step 6: Manually verify the bot runs (composition root — not automated)**

This step needs a real Telegram bot token from [@BotFather](https://t.me/BotFather) and is not part of the automated test suite:

```bash
export TELEGRAM_BOT_TOKEN="<token from BotFather>"
export SPLIT_DB_PATH="./split-manual-test.db"
./gradlew :telegram:run
```

(This requires adding the `application` plugin and a `mainClass` to `telegram/build.gradle.kts` — do that now if it's not already configured: add `id("application")` to the `plugins {}` block and `application { mainClass.set("split.telegram.BotApplicationKt") }` below `dependencies {}`.)

In Telegram, message the bot `/help`, then `/add 10 coffee` (mentioning yourself isn't needed — you're always included), then `/balances`. Confirm replies arrive. Stop with Ctrl+C when done; delete `split-manual-test.db*`.

- [ ] **Step 7: Commit**

```bash
git add telegram/src/main/kotlin/split/telegram/BotApplication.kt \
        telegram/src/test/kotlin/split/telegram/PollLoopSpec.kt \
        telegram/build.gradle.kts
git commit -m "Add poll loop and application bootstrap"
```

## Post-Task-16 review: HTML formatting

After all 16 tasks shipped, live manual testing showed `/list` output as an unreadable wall of plain text in the Telegram client. Fix: turn on Telegram's `parse_mode: HTML` on every outgoing message and format accordingly.

- `TelegramDtos.kt`'s `SendMessageRequest` gained a `parseMode: String` field (`@SerialName("parse_mode")`), deliberately with **no default value** — kotlinx.serialization omits default-valued fields from serialized output unless `encodeDefaults` is set, and this field must always be sent. `HttpTelegramApi.sendMessage` now always passes `parseMode = "HTML"`.
- `MessageFormatting.kt` gained `internal fun escapeHtml(text: String)` (escapes `&`, `<`, `>`) and applies it to every piece of user-controlled text — display names and expense descriptions — before interpolating it into a message. It also now wraps expense descriptions in `<b>...</b>` and `/list`'s short ids in `<code>...</code>`, and joins `/list` entries with a blank line (`"\n\n"`) instead of one line each.
- `MembersCommand.kt`'s `formatMembers` and `DeleteExpenseCommand.kt`'s two user-text interpolations (the deleted expense's description, and the `idPrefix` echoed back on a not-found error) now go through `escapeHtml` too, for the same reason.
- Several static strings we write ourselves used raw `<placeholder>` syntax, which HTML parse mode reads as an (invalid, unclosed) tag and mangles: `HELP_TEXT` (`HelpCommand.kt`), the `/add` and `/settle` usage messages (`CommandParsing.kt`), the `/currency` usage message (`CurrencyCommand.kt`), and the `/delete` usage message (`DeleteExpenseCommand.kt`) were all changed to use `<code>placeholder</code>` instead.
- `@mention` usernames (in `AddExpenseCommand.kt`, `SettleCommand.kt`) are not escaped — Telegram usernames are constrained to alphanumerics and underscores, so they can't contain HTML metacharacters.
- All affected tests (`HttpTelegramApiSpec`, `MessageFormattingSpec`, `ListCommandSpec`, `CurrencyCommandSpec`) were updated to match; the embedded code/test blocks above for Tasks 2, 3, 9, and 12–15 reflect the final, HTML-formatted versions.
- Follow-up during the same review round: the `/add` line's currency placeholder (`[CURRENCY]`) and the `/currency` line's own placeholder (`currency_code`, later `code`) were two different styles for the same concept — unified to lowercase `currency` wrapped in `<code>` in both places (`HELP_TEXT`, and the usage strings in `CommandParsing.kt`/`CurrencyCommand.kt`).
- Further follow-up: `formatExpenseList` now prefixes its output with `<b>Last 10 expenses:</b>\n\n`, naming the 10-expense cap `ListCommand` applies so it's not a mystery why an older expense is missing from the list.
- Follow-up: `HELP_TEXT` and the `/add`/`/settle` usage/error strings used bare `@mentions`/`@person`/`@alice`/`@bob` example text; since these are syntactically valid Telegram usernames (5+ letters/digits/underscores), Telegram's client read them as real mentions and linked/pinged whichever accounts actually held those usernames. Wrapped every `@`-prefixed placeholder in `<code>...</code>` too — Telegram can't nest other entities (including mentions) inside `<code>`/`<pre>`, so this stops the auto-detection the same way it already stopped `<amount>`-style text from being read as a broken HTML tag.

## Post-Task-16 review: @username preferred over display name

Follow-up to the `@`-in-help-text fix above, from the same conversation: static example text getting misread as a mention was one problem; the opposite gap was also raised — bot messages that name real, known members (`/list`, `/balances`, `/settle_suggest`, `/members`, the `/add` confirmation) showed the plain `displayName` even when the member's Telegram `@username` was already on file, when `@username` is the more idiomatic, clickable way to refer to someone in a Telegram message.

This turned out to be a bigger change than a formatting tweak, because `core.Member` only carries `id` and `displayName` — the Telegram username lives separately, in `platform_identity` via `PlatformDirectory`, and there was no way to look up "the usernames for this list of members" in bulk.

- `core.PlatformDirectory` gained `suspend fun findUsernames(platform: String, memberIds: List<MemberId>): Map<MemberId, String>`. A member absent from the returned map means either they have no linked platform identity yet, or their identity has no username set — callers fall back to `displayName` either way. Implemented in `ExposedPlatformDirectory` via a single `PlatformIdentityTable` query filtered by platform and `memberId inList ...`, keeping only rows with a non-null username.
- `MessageFormatting.kt` gained `internal fun mentionName(member: Member, usernames: Map<MemberId, String>): String`, used everywhere a member's name is rendered: `usernames[member.id]?.let { "@$it" } ?: escapeHtml(member.displayName)`. No escaping needed on the `@username` branch — Telegram usernames are constrained to letters, digits, and underscores. `formatExpenseConfirmation`, `formatExpenseList`, `formatBalances`, and `formatSettleSuggestions` each gained a trailing `usernames: Map<MemberId, String> = emptyMap()` parameter (defaulted so callers/tests that don't care about usernames are unaffected) and now call `mentionName` instead of `escapeHtml(...displayName)` directly. `MembersCommand.kt`'s `formatMembers` got the same treatment.
- Every command that formats a member list now fetches `platformDirectory.findUsernames(IdentityResolver.PLATFORM, members.map { it.id })` before formatting and threads it through: `MembersCommand`, `ListCommand`, `BalancesCommand`, `SettleSuggestCommand` all gained a `platformDirectory: PlatformDirectory` constructor parameter (`AddExpenseCommand` already had one). `BotApplication.kt`'s wiring was updated to pass it to all four.
- Test fallout: every test that resolves a member via `IdentityResolver.resolveMember(externalUserId, username, displayName)` with a non-null `username` now sees that member named as `@username` instead of the plain display name in any command-level assertion — `ListCommandSpec`, `BalancesCommandSpec`, `SettleSuggestCommandSpec`, and `MembersCommandSpec` were all updated accordingly (plus a couple of new tests confirming the plain-display-name fallback still works when `username` is `null`). `MessageFormattingSpec` gained direct unit tests for `mentionName` and for the `usernames` parameter on `formatExpenseConfirmation`/`formatBalances`/`formatSettleSuggestions`. `ExposedPlatformDirectorySpec` gained tests for `findUsernames`, including the empty-input and no-linked-identity cases.

## Post-Task-16 review: plain-text titles on every response, separated by a blank line

Follow-up in the same conversation: the `/list` title (`Last 10 expenses:`) was well received, but bold read as too visually loud next to the bold expense descriptions right below it — changed from `<b>Last 10 expenses:</b>` to plain `Last 10 expenses:`. The same title-then-blank-line pattern was then extended to every other command that sends the user informational content, for consistency:

- `formatExpenseConfirmation` → `"Expense added:\n\n..."`
- `formatBalances` (non-empty case only — `"You're all settled up!"` stays a single line) → `"Balances:\n\n..."`
- `formatSettleSuggestions` (non-empty case only — `"Everyone's settled up — nothing to do!"` stays a single line) → `"Suggested settlements:\n\n..."`
- `formatMembers` (non-empty case only — `"No members yet."` stays a single line) → `"Members:\n\n..."`
- `HELP_TEXT` — a blank line was inserted after the existing `Commands:` line, which was already acting as a title but butted straight up against the first command.
- `CurrencyCommand`'s success message → `"Currency updated:\n\nThis group's default currency is now $code."`
- `SettleCommand`'s success message → `"Settlement recorded:\n\nYou paid $amount."` (previously a single line starting with `"Recorded: ..."`)
- `DeleteExpenseCommand`'s success message → `"Expense deleted:\n\n\"$description\" ($amount)."` (previously `"Deleted \"$description\" ($amount)."` — the leading verb moved into the title so it isn't stated twice)

Deliberately **not** titled: the various `Usage: ...`, `"I don't recognize @...`, and other error/validation one-liners. Those are single sentences already, not content blocks, so a title-plus-blank-line would just add noise without helping readability — titles were reserved for messages that present the result of a successful action or a list of things.

All affected tests across `MessageFormattingSpec`, `ListCommandSpec`, `MembersCommandSpec`, `BalancesCommandSpec`, `SettleSuggestCommandSpec`, `CurrencyCommandSpec`, `SettleCommandSpec`, and `DeleteExpenseCommandSpec` were updated to match.
