package split.telegram

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.jdbc.Database
import split.storage.ExposedGroupRepository
import split.storage.ExposedMemberRepository
import split.storage.ExposedPlatformDirectory

class CommandRouterSpec : StringSpec({

    fun aResolver(db: Database) = IdentityResolver(
        ExposedPlatformDirectory(db),
        ExposedMemberRepository(db),
        ExposedGroupRepository(db),
    )

    fun anUpdate(text: String) = TgUpdate(
        updateId = 1,
        message = TgMessage(
            messageId = 1,
            from = TgUser(id = 1, firstName = "Alice"),
            chat = TgChat(id = -1, type = "group"),
            text = text,
        ),
    )

    "dispatches to the handler registered under the command name" {
        withTestDatabase { db ->
            val helpInvocations = mutableListOf<CommandContext>()
            val router = CommandRouter(aResolver(db), mapOf("help" to { context: CommandContext -> helpInvocations += context }))

            router.handleUpdate(anUpdate("/help"))

            helpInvocations.size shouldBe 1
        }
    }

    "passes the command's arguments through to the handler" {
        withTestDatabase { db ->
            val addInvocations = mutableListOf<CommandContext>()
            val router = CommandRouter(aResolver(db), mapOf("add" to { context: CommandContext -> addInvocations += context }))

            router.handleUpdate(anUpdate("/add 90 dinner"))

            addInvocations.single().args shouldBe "90 dinner"
        }
    }

    "does not dispatch to a handler registered under a different command name" {
        withTestDatabase { db ->
            val helpInvocations = mutableListOf<CommandContext>()
            val router = CommandRouter(aResolver(db), mapOf("help" to { context: CommandContext -> helpInvocations += context }))

            router.handleUpdate(anUpdate("/start"))

            helpInvocations shouldBe emptyList()
        }
    }

    "ignores non-command text" {
        withTestDatabase { db ->
            val helpInvocations = mutableListOf<CommandContext>()
            val router = CommandRouter(aResolver(db), mapOf("help" to { context: CommandContext -> helpInvocations += context }))

            router.handleUpdate(anUpdate("just chatting"))

            helpInvocations shouldBe emptyList()
        }
    }

    "ignores updates with no message or no sender" {
        withTestDatabase { db ->
            val helpInvocations = mutableListOf<CommandContext>()
            val router = CommandRouter(aResolver(db), mapOf("help" to { context: CommandContext -> helpInvocations += context }))

            router.handleUpdate(TgUpdate(updateId = 1, message = null))

            helpInvocations shouldBe emptyList()
        }
    }
})
