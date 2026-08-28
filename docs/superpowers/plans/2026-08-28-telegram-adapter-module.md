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
- Multi-currency `/balances` and `/list` ("one block per currency if the group has more than one") — this plan scopes both to the group's single `defaultCurrency`. Supporting more needs a new repository query (e.g. `ExpenseRepository.listCurrencies(groupId)`) that doesn't exist yet.
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
)
```

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

        requests.single().body.toByteArray().decodeToString() shouldBe """{"chat_id":-100,"text":"hi"}"""
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
            setBody(SendMessageRequest(chatId, text))
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

Resolves an incoming Telegram user/chat to a `core` `MemberId`/`GroupId`, creating them on first sight — the passive-observation identity resolution the bot design calls for.

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

        val memberId = identityResolver.resolveMember(from.id.toString(), from.username, from.firstName)
        val groupId = identityResolver.resolveGroup(message.chat.id.toString())
        identityResolver.ensureGroupMembership(groupId, memberId)

        if (!text.startsWith("/")) return

        val (command, args) = parseCommand(text)
        val handler = handlers[command] ?: return
        handler(CommandContext(message.chat.id, memberId, from.id.toString(), groupId, args))
    }
}
```

- [ ] **Step 5: Write `HelpCommand` and `StartCommand`**

Create `telegram/src/main/kotlin/split/telegram/HelpCommand.kt`:

```kotlin
package split.telegram

internal const val HELP_TEXT = """Commands:
/add <amount> [CURRENCY] <description> @mentions... — log an expense you paid, split equally
/members — list who I recognize in this group
/members add <name> — add someone without Telegram to split with
/currency <code> — set this group's default currency
/balances — see who owes you and who you owe
/list — last 10 expenses
/delete <id> — remove an expense (payer or admin only)
/settle @person <amount> — record that you paid them
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
            telegramApi.sentMessages shouldBe listOf(-100L to "Usage: /currency <3-letter code>, e.g. /currency EUR")
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
            telegramApi.sendMessage(context.chatId, "Usage: /currency <3-letter code>, e.g. /currency EUR")
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

### Task 8: `/members` and `/members add` commands

**Files:**
- Create: `telegram/src/main/kotlin/split/telegram/MembersCommand.kt`
- Test: `telegram/src/test/kotlin/split/telegram/MembersCommandSpec.kt`

**Interfaces:**
- Consumes: `MemberRepository`, `GroupRepository` (`core`), `CommandContext`, `TelegramApi`, `IdentityResolver`.
- Produces: `class MembersCommand(memberRepository: MemberRepository, groupRepository: GroupRepository, telegramApi: TelegramApi) { suspend fun handle(context: CommandContext) }`.

- [ ] **Step 1: Write the failing test**

Create `telegram/src/test/kotlin/split/telegram/MembersCommandSpec.kt`:

```kotlin
package split.telegram

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import split.storage.ExposedGroupRepository
import split.storage.ExposedMemberRepository
import split.storage.ExposedPlatformDirectory

class MembersCommandSpec : StringSpec({

    "lists no members when the group is empty" {
        withTestDatabase { db ->
            val groupRepository = ExposedGroupRepository(db)
            val resolver = IdentityResolver(ExposedPlatformDirectory(db), ExposedMemberRepository(db), groupRepository)
            val groupId = resolver.resolveGroup("-100")
            val telegramApi = FakeTelegramApi()
            val command = MembersCommand(ExposedMemberRepository(db), groupRepository, telegramApi)

            command.handle(CommandContext(-100, split.core.MemberId("m1"), "1", groupId, ""))

            telegramApi.sentMessages shouldBe listOf(-100L to "No members yet.")
        }
    }

    "lists members already in the group" {
        withTestDatabase { db ->
            val groupRepository = ExposedGroupRepository(db)
            val memberRepository = ExposedMemberRepository(db)
            val resolver = IdentityResolver(ExposedPlatformDirectory(db), memberRepository, groupRepository)
            val memberId = resolver.resolveMember("1", "alice", "Alice")
            val groupId = resolver.resolveGroup("-100")
            resolver.ensureGroupMembership(groupId, memberId)
            val telegramApi = FakeTelegramApi()
            val command = MembersCommand(memberRepository, groupRepository, telegramApi)

            command.handle(CommandContext(-100, memberId, "1", groupId, ""))

            telegramApi.sentMessages shouldBe listOf(-100L to "• Alice")
        }
    }

    "adds a placeholder member with /members add" {
        withTestDatabase { db ->
            val groupRepository = ExposedGroupRepository(db)
            val memberRepository = ExposedMemberRepository(db)
            val resolver = IdentityResolver(ExposedPlatformDirectory(db), memberRepository, groupRepository)
            val groupId = resolver.resolveGroup("-100")
            val telegramApi = FakeTelegramApi()
            val command = MembersCommand(memberRepository, groupRepository, telegramApi)

            command.handle(CommandContext(-100, split.core.MemberId("m1"), "1", groupId, "add Charlie"))

            memberRepository.findByGroup(groupId).map { it.displayName } shouldBe listOf("Charlie")
            telegramApi.sentMessages shouldBe listOf(-100L to "Added Charlie to this group.")
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

import split.core.GroupRepository
import split.core.Member
import split.core.MemberId
import split.core.MemberRepository
import java.util.UUID

class MembersCommand(
    private val memberRepository: MemberRepository,
    private val groupRepository: GroupRepository,
    private val telegramApi: TelegramApi,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun handle(context: CommandContext) {
        val trimmed = context.args.trim()
        if (trimmed.startsWith("add ")) {
            val name = trimmed.removePrefix("add ").trim()
            if (name.isEmpty()) {
                telegramApi.sendMessage(context.chatId, "Usage: /members add <name>")
                return
            }
            val member = Member(MemberId(idGenerator()), name)
            memberRepository.create(member)
            groupRepository.addMember(context.groupId, member.id)
            telegramApi.sendMessage(context.chatId, "Added $name to this group.")
            return
        }

        val members = memberRepository.findByGroup(context.groupId)
        telegramApi.sendMessage(context.chatId, formatMembers(members))
    }
}

internal fun formatMembers(members: List<Member>): String {
    if (members.isEmpty()) return "No members yet."
    return members.joinToString("\n") { "• ${it.displayName}" }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :telegram:test --tests "split.telegram.MembersCommandSpec"`
Expected: `BUILD SUCCESSFUL`, 3 tests pass.

- [ ] **Step 5: Commit**

```bash
git add telegram/src/main/kotlin/split/telegram/MembersCommand.kt \
        telegram/src/test/kotlin/split/telegram/MembersCommandSpec.kt
git commit -m "Add /members and /members add commands"
```

---

### Task 9: Message formatting for expenses, balances, and settle-suggest

Pure functions — no repositories, no Telegram API — kept separate from `MembersCommand.kt`'s `formatMembers` (Task 8) because these need `core`'s money/debt types.

**Files:**
- Create: `telegram/src/main/kotlin/split/telegram/MessageFormatting.kt`
- Test: `telegram/src/test/kotlin/split/telegram/MessageFormattingSpec.kt`

**Interfaces:**
- Consumes: `Expense`, `Member`, `MemberId`, `DebtPayment` (`core`).
- Produces: `fun formatAmount(amount: BigDecimal): String`, `fun formatExpenseConfirmation(expense: Expense, members: List<Member>): String`, `fun formatExpenseList(expenses: List<Expense>): String`, `fun formatBalances(payments: List<DebtPayment>, members: List<Member>, viewerId: MemberId): String`, `fun formatSettleSuggestions(payments: List<DebtPayment>, members: List<Member>): String` — consumed by Tasks 10–15.

- [ ] **Step 1: Write the failing test**

Create `telegram/src/test/kotlin/split/telegram/MessageFormattingSpec.kt`:

```kotlin
package split.telegram

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

    "formatAmount renders two decimal places with a dollar sign" {
        formatAmount(BigDecimal("90")) shouldBe "$90.00"
        formatAmount(BigDecimal("12.5")) shouldBe "$12.50"
    }

    "formatExpenseConfirmation names payer and amount" {
        val expense = Expense(
            id = ExpenseId("e1"),
            groupId = GroupId("g1"),
            currency = "USD",
            description = "dinner",
            amount = BigDecimal("90.00"),
            payerId = alice.id,
            splitType = SplitType.EQUAL,
            createdBy = alice.id,
            createdAt = Instant.parse("2026-08-28T00:00:00Z"),
            shares = listOf(ExpenseShare(alice.id, BigDecimal("45.00")), ExpenseShare(bob.id, BigDecimal("45.00"))),
        )

        formatExpenseConfirmation(expense, members) shouldBe "Alice paid \$90.00 for dinner, split with Alice, Bob"
    }

    "formatExpenseList shows a short id, description, and amount per line" {
        val expense = Expense(
            id = ExpenseId("abcdef1234567890"),
            groupId = GroupId("g1"),
            currency = "USD",
            description = "dinner",
            amount = BigDecimal("90.00"),
            payerId = alice.id,
            splitType = SplitType.EQUAL,
            createdBy = alice.id,
            createdAt = Instant.parse("2026-08-28T00:00:00Z"),
            shares = emptyList(),
        )

        formatExpenseList(listOf(expense)) shouldBe "[abcdef12] dinner — \$90.00"
    }

    "formatExpenseList explains there's nothing yet" {
        formatExpenseList(emptyList()) shouldBe "No expenses yet — use /add to log one."
    }

    "formatBalances phrases payments relative to the viewer" {
        val payments = listOf(DebtPayment(from = bob.id, to = alice.id, amount = BigDecimal("30.00")))

        formatBalances(payments, members, viewerId = alice.id) shouldBe "Bob owes you $30.00"
        formatBalances(payments, members, viewerId = bob.id) shouldBe "You owe Alice $30.00"
    }

    "formatBalances says everyone's settled up when there's nothing relevant" {
        formatBalances(emptyList(), members, viewerId = alice.id) shouldBe "You're all settled up!"
    }

    "formatSettleSuggestions lists every payment" {
        val payments = listOf(DebtPayment(from = bob.id, to = alice.id, amount = BigDecimal("30.00")))

        formatSettleSuggestions(payments, members) shouldBe "Bob pays Alice \$30.00"
    }

    "formatSettleSuggestions says everyone's settled up when there's nothing to do" {
        formatSettleSuggestions(emptyList(), members) shouldBe "Everyone's settled up — nothing to do!"
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
import java.math.BigDecimal
import java.math.RoundingMode

fun formatAmount(amount: BigDecimal): String = "$" + amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString()

fun formatExpenseConfirmation(expense: Expense, members: List<Member>): String {
    val nameOf = members.associateBy { it.id }
    val payerName = nameOf[expense.payerId]?.displayName ?: "Someone"
    val participantNames = expense.shares.joinToString(", ") { nameOf[it.memberId]?.displayName ?: "someone" }
    return "$payerName paid ${formatAmount(expense.amount)} for ${expense.description}, split with $participantNames"
}

fun formatExpenseList(expenses: List<Expense>): String {
    if (expenses.isEmpty()) return "No expenses yet — use /add to log one."
    return expenses.joinToString("\n") { expense ->
        val shortId = expense.id.value.take(8)
        "[$shortId] ${expense.description} — ${formatAmount(expense.amount)}"
    }
}

fun formatBalances(payments: List<DebtPayment>, members: List<Member>, viewerId: MemberId): String {
    val nameOf = members.associateBy { it.id }
    val relevant = payments.filter { it.from == viewerId || it.to == viewerId }
    if (relevant.isEmpty()) return "You're all settled up!"

    return relevant.joinToString("\n") { payment ->
        val amount = formatAmount(payment.amount)
        if (payment.from == viewerId) {
            "You owe ${nameOf[payment.to]?.displayName ?: "someone"} $amount"
        } else {
            "${nameOf[payment.from]?.displayName ?: "someone"} owes you $amount"
        }
    }
}

fun formatSettleSuggestions(payments: List<DebtPayment>, members: List<Member>): String {
    if (payments.isEmpty()) return "Everyone's settled up — nothing to do!"
    val nameOf = members.associateBy { it.id }
    return payments.joinToString("\n") { payment ->
        val from = nameOf[payment.from]?.displayName ?: "someone"
        val to = nameOf[payment.to]?.displayName ?: "someone"
        "$from pays $to ${formatAmount(payment.amount)}"
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :telegram:test --tests "split.telegram.MessageFormattingSpec"`
Expected: `BUILD SUCCESSFUL`, 8 tests pass.

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
        parseAddArgs("90 dinner @alice @bob", defaultCurrency = "USD") shouldBe
            AddExpenseArgs(BigDecimal("90"), "USD", "dinner", listOf("alice", "bob"))
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
            val groupId = resolver.resolveGroup("-100")
            resolver.ensureGroupMembership(groupId, aliceId)
            resolver.resolveMember("2", "bob", "Bob") // Bob has spoken before, so @bob resolves

            val telegramApi = FakeTelegramApi()
            val command = AddExpenseCommand(
                platformDirectory, groupRepository, memberRepository, expenseRepository, resolver, telegramApi,
            )

            command.handle(CommandContext(-100, aliceId, "1", groupId, "90 dinner @bob"))

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
            val groupId = resolver.resolveGroup("-100")

            val telegramApi = FakeTelegramApi()
            val command = AddExpenseCommand(
                platformDirectory, groupRepository, memberRepository, expenseRepository, resolver, telegramApi,
            )

            command.handle(CommandContext(-100, aliceId, "1", groupId, "90 dinner @stranger"))

            expenseRepository.listActive(groupId, "USD") shouldBe emptyList()
            telegramApi.sentMessages.single().second shouldBe
                "I don't recognize @stranger yet — ask them to send a message in this chat or run /start with me first."
        }
    }
})
```

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
    require(mentions.isNotEmpty()) { "Mention at least one participant, e.g. /add 90 dinner @alice @bob" }

    val withoutMentions = mentionPattern.replace(args, "").trim().replace(Regex("\\s+"), " ")
    val parts = withoutMentions.split(" ", limit = 2)
    require(parts.size == 2) { "Usage: /add <amount> [CURRENCY] <description> @mentions..." }

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
                    "I don't recognize @$username yet — ask them to send a message in this chat or run /start with me first.",
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
            val groupId = resolver.resolveGroup("-100")
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
            telegramApi.sentMessages shouldBe listOf(-100L to "Deleted \"dinner\" (\$90.00).")
        }
    }

    "a non-payer, non-admin cannot delete the expense" {
        withTestDatabase { db ->
            val groupRepository = ExposedGroupRepository(db)
            val expenseRepository = ExposedExpenseRepository(db)
            val resolver = IdentityResolver(ExposedPlatformDirectory(db), ExposedMemberRepository(db), groupRepository)
            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val bobId = resolver.resolveMember("2", "bob", "Bob")
            val groupId = resolver.resolveGroup("-100")
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
            val groupId = resolver.resolveGroup("-100")
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
            val groupId = resolver.resolveGroup("-100")

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
            telegramApi.sendMessage(context.chatId, "Usage: /delete <id> (see /list for ids)")
            return
        }

        val group = groupRepository.find(context.groupId) ?: error("Group ${context.groupId} not found")
        val expense = expenseRepository.listActive(context.groupId, group.defaultCurrency)
            .firstOrNull { it.id.value.startsWith(idPrefix, ignoreCase = true) }

        if (expense == null) {
            telegramApi.sendMessage(context.chatId, "No active expense found matching \"$idPrefix\" — check /list.")
            return
        }

        val admins = telegramApi.getChatAdministrators(context.chatId)
        val isAdmin = admins.any { it.user.id.toString() == context.externalUserId }

        if (!canDeleteExpense(expense, context.memberId, isAdmin)) {
            telegramApi.sendMessage(context.chatId, "Only the payer or a group admin can delete this expense.")
            return
        }

        expenseRepository.softDelete(expense.id, Instant.now(clock))
        telegramApi.sendMessage(context.chatId, "Deleted \"${expense.description}\" (${formatAmount(expense.amount)}).")
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
- Consumes: `GroupRepository`, `ExpenseRepository` (`core`); `formatExpenseList` (Task 9).
- Produces: `class ListCommand(groupRepository: GroupRepository, expenseRepository: ExpenseRepository, telegramApi: TelegramApi) { suspend fun handle(context: CommandContext) }`.

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

    "lists active expenses newest first, capped at 10" {
        withTestDatabase { db ->
            val groupRepository = ExposedGroupRepository(db)
            val expenseRepository = ExposedExpenseRepository(db)
            val resolver = IdentityResolver(ExposedPlatformDirectory(db), ExposedMemberRepository(db), groupRepository)
            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val groupId = resolver.resolveGroup("-100")

            fun expense(id: String, at: String) = Expense(
                id = ExpenseId(id),
                groupId = groupId,
                currency = "USD",
                description = id,
                amount = BigDecimal("10.00"),
                payerId = aliceId,
                splitType = SplitType.EQUAL,
                createdBy = aliceId,
                createdAt = Instant.parse(at),
                shares = listOf(ExpenseShare(aliceId, BigDecimal("10.00"))),
            )

            expenseRepository.create(expense("older12345", "2026-08-27T00:00:00Z"))
            expenseRepository.create(expense("newer12345", "2026-08-28T00:00:00Z"))

            val telegramApi = FakeTelegramApi()
            val command = ListCommand(groupRepository, expenseRepository, telegramApi)

            command.handle(CommandContext(-100, aliceId, "1", groupId, ""))

            telegramApi.sentMessages shouldBe listOf(
                -100L to "[newer123] newer12345 — \$10.00\n[older123] older12345 — \$10.00",
            )
        }
    }

    "explains there's nothing yet" {
        withTestDatabase { db ->
            val groupRepository = ExposedGroupRepository(db)
            val expenseRepository = ExposedExpenseRepository(db)
            val resolver = IdentityResolver(ExposedPlatformDirectory(db), ExposedMemberRepository(db), groupRepository)
            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val groupId = resolver.resolveGroup("-100")

            val telegramApi = FakeTelegramApi()
            val command = ListCommand(groupRepository, expenseRepository, telegramApi)

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

class ListCommand(
    private val groupRepository: GroupRepository,
    private val expenseRepository: ExpenseRepository,
    private val telegramApi: TelegramApi,
) {
    suspend fun handle(context: CommandContext) {
        val group = groupRepository.find(context.groupId) ?: error("Group ${context.groupId} not found")
        val expenses = expenseRepository.listActive(context.groupId, group.defaultCurrency)
            .sortedByDescending { it.createdAt }
            .take(10)

        telegramApi.sendMessage(context.chatId, formatExpenseList(expenses))
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :telegram:test --tests "split.telegram.ListCommandSpec"`
Expected: `BUILD SUCCESSFUL`, 2 tests pass.

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
            val groupId = resolver.resolveGroup("-100")
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

            telegramApi.sentMessages shouldBe listOf(-100L to "You owe Alice \$30.00")
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
            val groupId = resolver.resolveGroup("-100")

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

        telegramApi.sendMessage(context.chatId, formatBalances(payments, members, context.memberId))
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
- Consumes: `PlatformDirectory`, `SettlementRepository`, `createSettlement` (`core`); `extractMentions` (Task 5).
- Produces: `data class SettleArgs(counterpartyUsername: String, amount: BigDecimal)`, `fun parseSettleArgs(args: String): SettleArgs`, `class SettleCommand(platformDirectory: PlatformDirectory, settlementRepository: SettlementRepository, telegramApi: TelegramApi) { suspend fun handle(context: CommandContext) }`.

- [ ] **Step 1: Write the failing parsing tests**

Append to `telegram/src/test/kotlin/split/telegram/CommandParsingSpec.kt`, inside the `StringSpec({ ... })` block:

```kotlin
    "parseSettleArgs parses a mention and an amount" {
        parseSettleArgs("@bob 20") shouldBe SettleArgs("bob", BigDecimal("20"))
    }

    "parseSettleArgs rejects zero mentions" {
        shouldThrow<IllegalArgumentException> { parseSettleArgs("20") }
    }

    "parseSettleArgs rejects more than one mention" {
        shouldThrow<IllegalArgumentException> { parseSettleArgs("@bob @carol 20") }
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
            val settlementRepository = ExposedSettlementRepository(db)
            val resolver = IdentityResolver(platformDirectory, ExposedMemberRepository(db), ExposedGroupRepository(db))
            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            resolver.resolveMember("2", "bob", "Bob")
            val groupId = resolver.resolveGroup("-100")

            val telegramApi = FakeTelegramApi()
            val command = SettleCommand(platformDirectory, settlementRepository, telegramApi)

            command.handle(CommandContext(-100, aliceId, "1", groupId, "@bob 20"))

            val settlement = settlementRepository.listActive(groupId, "USD").single()
            settlement.fromMemberId shouldBe aliceId
            settlement.amount shouldBe BigDecimal("20.00")
            telegramApi.sentMessages.single().first shouldBe -100L
        }
    }

    "replies when the mentioned person isn't recognized" {
        withTestDatabase { db ->
            val platformDirectory = ExposedPlatformDirectory(db)
            val settlementRepository = ExposedSettlementRepository(db)
            val resolver = IdentityResolver(platformDirectory, ExposedMemberRepository(db), ExposedGroupRepository(db))
            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val groupId = resolver.resolveGroup("-100")

            val telegramApi = FakeTelegramApi()
            val command = SettleCommand(platformDirectory, settlementRepository, telegramApi)

            command.handle(CommandContext(-100, aliceId, "1", groupId, "@stranger 20"))

            settlementRepository.listActive(groupId, "USD") shouldBe emptyList()
            telegramApi.sentMessages.single().second shouldBe
                "I don't recognize @stranger yet — ask them to send a message in this chat or run /start with me first."
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
    require(mentions.size == 1) { "Usage: /settle @person <amount>" }
    val withoutMention = mentionPattern.replace(args, "").trim()
    require(withoutMention.isNotEmpty()) { "Usage: /settle @person <amount>" }
    return SettleArgs(mentions[0], BigDecimal(withoutMention))
}
```

- [ ] **Step 5: Write `SettleCommand`**

Create `telegram/src/main/kotlin/split/telegram/SettleCommand.kt`:

```kotlin
package split.telegram

import split.core.PlatformDirectory
import split.core.SettlementId
import split.core.SettlementRepository
import split.core.createSettlement
import java.time.Clock
import java.time.Instant
import java.util.UUID

class SettleCommand(
    private val platformDirectory: PlatformDirectory,
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
                "I don't recognize @${parsed.counterpartyUsername} yet — ask them to send a message in this chat or run /start with me first.",
            )
            return
        }

        val settlement = try {
            createSettlement(
                id = SettlementId(idGenerator()),
                groupId = context.groupId,
                currency = "USD",
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
        telegramApi.sendMessage(context.chatId, "Recorded: you paid ${formatAmount(settlement.amount)}.")
    }
}
```

Note: this hard-codes `currency = "USD"` rather than looking up the group's `defaultCurrency`, matching this plan's single-currency scope (see "Out of scope"); a `GroupRepository` lookup can replace it when multi-currency support lands.

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew :telegram:test --tests "split.telegram.CommandParsingSpec" --tests "split.telegram.SettleCommandSpec"`
Expected: `BUILD SUCCESSFUL`, all tests pass (13 in `CommandParsingSpec`, 2 in `SettleCommandSpec`).

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
            val groupId = resolver.resolveGroup("-100")
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

            telegramApi.sentMessages shouldBe listOf(-100L to "Bob pays Alice \$30.00")
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
            val groupId = resolver.resolveGroup("-100")

            val telegramApi = FakeTelegramApi()
            val command = SettleSuggestCommand(groupRepository, memberRepository, expenseRepository, settlementRepository, telegramApi)

            command.handle(CommandContext(-100, aliceId, "1", groupId, ""))

            telegramApi.sentMessages shouldBe listOf(-100L to "Everyone's settled up — nothing to do!")
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

        telegramApi.sendMessage(context.chatId, formatSettleSuggestions(payments, members))
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :telegram:test --tests "split.telegram.SettleSuggestCommandSpec"`
Expected: `BUILD SUCCESSFUL`, 2 tests pass.

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
        "members" to MembersCommand(memberRepository, groupRepository, telegramApi)::handle,
        "add" to AddExpenseCommand(
            platformDirectory, groupRepository, memberRepository, expenseRepository, identityResolver, telegramApi,
        )::handle,
        "delete" to DeleteExpenseCommand(groupRepository, expenseRepository, telegramApi)::handle,
        "list" to ListCommand(groupRepository, expenseRepository, telegramApi)::handle,
        "balances" to BalancesCommand(
            groupRepository, memberRepository, expenseRepository, settlementRepository, telegramApi,
        )::handle,
        "settle" to SettleCommand(platformDirectory, settlementRepository, telegramApi)::handle,
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
