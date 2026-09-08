package split.telegram

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.jdbc.Database
import split.storage.ExposedGroupRepository
import split.storage.ExposedMemberRepository
import split.storage.ExposedPlatformDirectory

class CommandRouterSpec :
    StringSpec({

        fun aResolver(db: Database) =
            IdentityResolver(
                ExposedPlatformDirectory(db),
                ExposedMemberRepository(db),
                ExposedGroupRepository(db),
            )

        fun anUpdate(text: String) =
            TgUpdate(
                updateId = 1,
                message =
                    TgMessage(
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

        "does not register identity for non-command text" {
            withTestDatabase { db ->
                val platformDirectory = ExposedPlatformDirectory(db)
                val resolver = IdentityResolver(platformDirectory, ExposedMemberRepository(db), ExposedGroupRepository(db))
                val router = CommandRouter(resolver, mapOf("help" to { _: CommandContext -> }))

                router.handleUpdate(anUpdate("just chatting"))

                platformDirectory.findMember("telegram", "1") shouldBe null
            }
        }

        "does not register identity for an unrecognized command" {
            withTestDatabase { db ->
                val platformDirectory = ExposedPlatformDirectory(db)
                val resolver = IdentityResolver(platformDirectory, ExposedMemberRepository(db), ExposedGroupRepository(db))
                val router = CommandRouter(resolver, mapOf("help" to { _: CommandContext -> }))

                router.handleUpdate(anUpdate("/unknown"))

                platformDirectory.findMember("telegram", "1") shouldBe null
            }
        }

        "dispatches a callback_query to the registered callback handler" {
            withTestDatabase { db ->
                val received = mutableListOf<CallbackContext>()
                val callbacks = CallbackRouting(flowHandler = { received += it })
                val router = CommandRouter(aResolver(db), emptyMap(), callbacks = callbacks)

                router.handleUpdate(
                    TgUpdate(
                        updateId = 1,
                        callbackQuery =
                            TgCallbackQuery(
                                id = "cbq1",
                                from = TgUser(id = 1, firstName = "Alice"),
                                message = TgMessage(messageId = 42, chat = TgChat(id = -1, type = "group")),
                                data = "split:mode:equal",
                            ),
                    ),
                )

                received.single() shouldBe
                    CallbackContext(
                        chatId = -1,
                        memberId = received.single().memberId,
                        groupId = received.single().groupId,
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
                        callbackQuery =
                            TgCallbackQuery(
                                id = "cbq1",
                                from = TgUser(id = 1, firstName = "Alice"),
                                message = TgMessage(messageId = 42, chat = TgChat(id = -1, type = "group")),
                                data = "split:mode:equal",
                            ),
                    ),
                )
            }
        }

        "ignores a callback_query with no message or no data" {
            withTestDatabase { db ->
                val received = mutableListOf<CallbackContext>()
                val callbacks = CallbackRouting(flowHandler = { received += it })
                val router = CommandRouter(aResolver(db), emptyMap(), callbacks = callbacks)

                router.handleUpdate(
                    TgUpdate(
                        updateId = 1,
                        callbackQuery =
                            TgCallbackQuery(
                                id = "cbq1",
                                from = TgUser(id = 1, firstName = "Alice"),
                                message = null,
                                data = "x",
                            ),
                    ),
                )
                router.handleUpdate(
                    TgUpdate(
                        updateId = 2,
                        callbackQuery =
                            TgCallbackQuery(
                                id = "cbq2",
                                from = TgUser(id = 1, firstName = "Alice"),
                                message = TgMessage(messageId = 42, chat = TgChat(id = -1, type = "group")),
                                data = null,
                            ),
                    ),
                )

                received shouldBe emptyList()
            }
        }

        "dispatches a non-command reply to the registered reply handler" {
            withTestDatabase { db ->
                val replies = mutableListOf<ReplyContext>()
                val reply = ReplyRouting(handler = { replies += it }, isTracked = { _, _ -> true })
                val router = CommandRouter(aResolver(db), emptyMap(), reply = reply)

                router.handleUpdate(
                    TgUpdate(
                        updateId = 1,
                        message =
                            TgMessage(
                                messageId = 7,
                                from = TgUser(id = 1, firstName = "Alice"),
                                chat = TgChat(id = -1, type = "group"),
                                text = "50",
                                replyToMessage = TgMessage(messageId = 3, chat = TgChat(id = -1, type = "group")),
                            ),
                    ),
                )

                replies.single() shouldBe
                    ReplyContext(
                        chatId = -1,
                        memberId = replies.single().memberId,
                        groupId = replies.single().groupId,
                        replyToMessageId = 3,
                        text = "50",
                    )
            }
        }

        "ignores a reply to an untracked message and registers no identity" {
            withTestDatabase { db ->
                val platformDirectory = ExposedPlatformDirectory(db)
                val resolver = IdentityResolver(platformDirectory, ExposedMemberRepository(db), ExposedGroupRepository(db))
                val replies = mutableListOf<ReplyContext>()
                val router = CommandRouter(resolver, emptyMap(), reply = ReplyRouting(handler = { replies += it }))

                router.handleUpdate(
                    TgUpdate(
                        updateId = 1,
                        message =
                            TgMessage(
                                messageId = 7,
                                from = TgUser(id = 1, firstName = "Alice"),
                                chat = TgChat(id = -1, type = "group"),
                                text = "50",
                                replyToMessage = TgMessage(messageId = 3, chat = TgChat(id = -1, type = "group")),
                            ),
                    ),
                )

                replies shouldBe emptyList()
                platformDirectory.findMember("telegram", "1") shouldBe null
            }
        }

        "does not treat a command as a reply even when it replies to a message" {
            withTestDatabase { db ->
                val replies = mutableListOf<ReplyContext>()
                val helpInvocations = mutableListOf<CommandContext>()
                val router =
                    CommandRouter(
                        aResolver(db),
                        mapOf("help" to { context: CommandContext -> helpInvocations += context }),
                        reply = ReplyRouting(handler = { replies += it }),
                    )

                router.handleUpdate(
                    TgUpdate(
                        updateId = 1,
                        message =
                            TgMessage(
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
                val router = CommandRouter(aResolver(db), emptyMap(), reply = ReplyRouting(handler = { replies += it }))

                router.handleUpdate(anUpdate("just chatting"))

                replies shouldBe emptyList()
            }
        }

        "dispatches a callback_query with matching data to a static callback handler instead of the flow handler" {
            withTestDatabase { db ->
                val staticCallbacks = mutableListOf<CallbackContext>()
                val flowCallbacks = mutableListOf<CallbackContext>()
                val helpHandler: CallbackHandler = { context -> staticCallbacks += context }
                val router =
                    CommandRouter(
                        aResolver(db),
                        emptyMap(),
                        callbacks =
                            CallbackRouting(
                                flowHandler = { flowCallbacks += it },
                                staticHandlers = mapOf("help" to helpHandler),
                            ),
                    )

                router.handleUpdate(
                    TgUpdate(
                        updateId = 1,
                        callbackQuery =
                            TgCallbackQuery(
                                id = "cbq1",
                                from = TgUser(id = 1, firstName = "Alice"),
                                message = TgMessage(messageId = 42, chat = TgChat(id = -1, type = "group")),
                                data = "help",
                            ),
                    ),
                )

                staticCallbacks.single().data shouldBe "help"
                flowCallbacks shouldBe emptyList()
            }
        }

        "a private-chat command dispatches to the matching dmHandler, resolving no group identity" {
            withTestDatabase { db ->
                val platformDirectory = ExposedPlatformDirectory(db)
                val dmInvocations = mutableListOf<DmCommandContext>()
                val dmStartHandler: DmCommandHandler = { context -> dmInvocations += context }
                val router =
                    CommandRouter(
                        aResolver(db),
                        mapOf("start" to { _: CommandContext -> }),
                        dm = DmRouting(handlers = mapOf("start" to dmStartHandler)),
                    )

                router.handleUpdate(
                    TgUpdate(
                        updateId = 1,
                        message =
                            TgMessage(
                                messageId = 1,
                                from = TgUser(id = 1, firstName = "Alice"),
                                chat = TgChat(id = 555, type = "private"),
                                text = "/start",
                            ),
                    ),
                )

                dmInvocations.single().chatId shouldBe 555L
                platformDirectory.findGroup("telegram", "555") shouldBe null
            }
        }

        "an unrecognized private-chat command falls back to the dmFallbackHandler" {
            withTestDatabase { db ->
                val fallbackInvocations = mutableListOf<DmCommandContext>()
                val router =
                    CommandRouter(
                        aResolver(db),
                        emptyMap(),
                        dm = DmRouting(fallbackHandler = { context -> fallbackInvocations += context }),
                    )

                router.handleUpdate(
                    TgUpdate(
                        updateId = 1,
                        message =
                            TgMessage(
                                messageId = 1,
                                from = TgUser(id = 1, firstName = "Alice"),
                                chat = TgChat(id = 555, type = "private"),
                                text = "/split",
                            ),
                    ),
                )

                fallbackInvocations.single().args shouldBe ""
            }
        }

        "an unrecognized private-chat command does nothing when no dmFallbackHandler is registered" {
            withTestDatabase { db ->
                val platformDirectory = ExposedPlatformDirectory(db)
                val router = CommandRouter(aResolver(db), emptyMap())

                router.handleUpdate(
                    TgUpdate(
                        updateId = 1,
                        message =
                            TgMessage(
                                messageId = 1,
                                from = TgUser(id = 1, firstName = "Alice"),
                                chat = TgChat(id = 555, type = "private"),
                                text = "/split",
                            ),
                    ),
                )

                platformDirectory.findMember("telegram", "1") shouldBe null
            }
        }

        "the bot being added to a group invokes the groupJoinHandler" {
            withTestDatabase { db ->
                val joinInvocations = mutableListOf<GroupJoinContext>()
                val router = CommandRouter(aResolver(db), emptyMap(), groupJoinHandler = { joinInvocations += it })
                val splitBot = TgUser(id = 42, firstName = "SplitBot")

                router.handleUpdate(
                    TgUpdate(
                        updateId = 1,
                        myChatMember =
                            TgChatMemberUpdated(
                                chat = TgChat(id = -900, type = "supergroup"),
                                oldChatMember = TgChatMember(status = "left", user = splitBot),
                                newChatMember = TgChatMember(status = "member", user = splitBot),
                            ),
                    ),
                )

                joinInvocations.single().chatId shouldBe -900L
            }
        }

        "the bot being removed from a group does not invoke the groupJoinHandler" {
            withTestDatabase { db ->
                val joinInvocations = mutableListOf<GroupJoinContext>()
                val router = CommandRouter(aResolver(db), emptyMap(), groupJoinHandler = { joinInvocations += it })
                val splitBot = TgUser(id = 42, firstName = "SplitBot")

                router.handleUpdate(
                    TgUpdate(
                        updateId = 1,
                        myChatMember =
                            TgChatMemberUpdated(
                                chat = TgChat(id = -900, type = "supergroup"),
                                oldChatMember = TgChatMember(status = "member", user = splitBot),
                                newChatMember = TgChatMember(status = "left", user = splitBot),
                            ),
                    ),
                )

                joinInvocations shouldBe emptyList()
            }
        }

        "a user starting the bot in a private chat does not invoke the groupJoinHandler" {
            withTestDatabase { db ->
                val joinInvocations = mutableListOf<GroupJoinContext>()
                val router = CommandRouter(aResolver(db), emptyMap(), groupJoinHandler = { joinInvocations += it })
                val splitBot = TgUser(id = 42, firstName = "SplitBot")

                router.handleUpdate(
                    TgUpdate(
                        updateId = 1,
                        myChatMember =
                            TgChatMemberUpdated(
                                chat = TgChat(id = 555, type = "private"),
                                oldChatMember = TgChatMember(status = "left", user = splitBot),
                                newChatMember = TgChatMember(status = "member", user = splitBot),
                            ),
                    ),
                )

                joinInvocations shouldBe emptyList()
            }
        }
    })
