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
import split.telegram.GroupJoinContext
import split.telegram.IdentityResolver
import split.telegram.api.InlineKeyboardButton
import split.telegram.api.InlineKeyboardMarkup
import split.telegram.withTestDatabase

private const val GROUP_JOIN_TEXT =
    "👋 Hi! I split expenses for this group.\n\n" +
        "One thing first: everyone who'll be <code>@mentioned</code> in /split needs to send me /start — " +
        "type /start here to register yourself.\n\n" +
        "This group's default currency is USD — change it anytime with /currency.\n\n" +
        "Tap below to see everything I can do."

private val WELCOME_KEYBOARD =
    InlineKeyboardMarkup(
        inlineKeyboard =
            listOf(
                listOf(InlineKeyboardButton(text = "📋 Commands", callbackData = COMMANDS_CALLBACK_DATA)),
            ),
    )

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

        "StartCommand nudges to get others registered when the caller isn't a resolved member" {
            withTestDatabase { db ->
                val groupRepository = ExposedGroupRepository(db)
                val memberRepository = ExposedMemberRepository(db)
                val platformDirectory = ExposedPlatformDirectory(db)
                val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)
                val groupId = resolver.resolveGroup("-100001")
                val telegramApi = FakeTelegramApi()

                StartCommand(groupRepository, memberRepository, platformDirectory, telegramApi)
                    .handle(context.copy(groupId = groupId))

                telegramApi.sentMessages shouldBe
                    listOf(
                        -1L to
                            "✅ You're registered. Nobody else in the group has registered yet — " +
                            "ask everyone to run /start too.",
                    )
                telegramApi.sentKeyboards shouldBe listOf(null)
            }
        }

        "StartCommand greets the caller by their own @username when nudging to get others registered" {
            withTestDatabase { db ->
                val groupRepository = ExposedGroupRepository(db)
                val memberRepository = ExposedMemberRepository(db)
                val platformDirectory = ExposedPlatformDirectory(db)
                val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)
                val aliceId = resolver.resolveMember("1", "alice", "Alice")
                val groupId = resolver.resolveGroup("-100001")
                resolver.ensureGroupMembership(groupId, aliceId)
                val telegramApi = FakeTelegramApi()

                StartCommand(groupRepository, memberRepository, platformDirectory, telegramApi)
                    .handle(context.copy(memberId = aliceId, groupId = groupId))

                telegramApi.sentMessages shouldBe
                    listOf(
                        -1L to
                            "✅ You're registered, @alice. Nobody else in the group has registered yet — " +
                            "ask everyone to run /start too.",
                    )
            }
        }

        "StartCommand lists who else is already registered once there's more than just the caller" {
            withTestDatabase { db ->
                val groupRepository = ExposedGroupRepository(db)
                val memberRepository = ExposedMemberRepository(db)
                val platformDirectory = ExposedPlatformDirectory(db)
                val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)
                val aliceId = resolver.resolveMember("1", "alice", "Alice")
                val bobId = resolver.resolveMember("2", "bob", "Bob")
                val groupId = resolver.resolveGroup("-100001")
                resolver.ensureGroupMembership(groupId, aliceId)
                resolver.ensureGroupMembership(groupId, bobId)
                val telegramApi = FakeTelegramApi()

                StartCommand(groupRepository, memberRepository, platformDirectory, telegramApi)
                    .handle(context.copy(memberId = bobId, groupId = groupId))

                telegramApi.sentMessages shouldBe
                    listOf(
                        -1L to
                            "✅ You're registered, @bob — I'll recognize you when you're <code>@mentioned</code> " +
                            "in /split.\n\nHere's who's registered already: @alice, @bob\n" +
                            "— /members to check out members.",
                    )
            }
        }

        "StartCommand.welcomeNewGroup tells the group to /start rather than claiming anyone already has" {
            withTestDatabase { db ->
                val groupRepository = ExposedGroupRepository(db)
                val memberRepository = ExposedMemberRepository(db)
                val platformDirectory = ExposedPlatformDirectory(db)
                val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)
                val groupId = resolver.resolveGroup("-100002")
                val telegramApi = FakeTelegramApi()

                val joinContext = GroupJoinContext(chatId = -2, groupId = groupId)
                StartCommand(groupRepository, memberRepository, platformDirectory, telegramApi)
                    .welcomeNewGroup(joinContext)

                telegramApi.sentMessages shouldBe listOf(-2L to GROUP_JOIN_TEXT)
                telegramApi.sentKeyboards shouldBe listOf(WELCOME_KEYBOARD)
            }
        }
    })
