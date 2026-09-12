package split.telegram.commands

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import split.core.GroupId
import split.core.MemberId
import split.storage.ExposedGroupRepository
import split.storage.ExposedMemberRepository
import split.storage.ExposedPlatformDirectory
import split.telegram.CommandContext
import split.telegram.FakeTelegramApi
import split.telegram.IdentityResolver
import split.telegram.split
import split.telegram.withTestDatabase

class HelpCommandSpec :
    StringSpec({

        val context =
            CommandContext(
                chatId = -1,
                memberId = MemberId("m1"),
                externalUserId = "1",
                groupId = GroupId("g1"),
                args = "",
            )

        "HelpCommand sends the help text" {
            val telegramApi = FakeTelegramApi()

            HelpCommand(telegramApi).handle(context)

            telegramApi.sentMessages shouldBe listOf(-1L to HELP_TEXT)
        }

        "StartCommand states the group's default currency and the /start-first rule, then the help text" {
            withTestDatabase { db ->
                val groupRepository = ExposedGroupRepository(db)
                val resolver = IdentityResolver(ExposedPlatformDirectory(db), ExposedMemberRepository(db), groupRepository)
                val groupId = resolver.resolveGroup("-100001")
                val telegramApi = FakeTelegramApi()

                StartCommand(groupRepository, telegramApi).handle(context.copy(groupId = groupId))

                telegramApi.sentMessages shouldBe
                    listOf(
                        -1L to (
                            "Hi! I'll help you split expenses in this group.\n\n" +
                                "This group's default currency is USD — change it anytime with /currency.\n\n" +
                                "Before you can mention someone in /split, they need to send me /start too.\n\n" +
                                HELP_TEXT
                        ),
                    )
            }
        }
    })
