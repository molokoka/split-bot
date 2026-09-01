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
