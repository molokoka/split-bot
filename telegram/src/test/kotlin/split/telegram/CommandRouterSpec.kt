package split.telegram

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import split.core.GroupId
import split.core.MemberId
import split.telegram.api.TgCallbackQuery
import split.telegram.api.TgChat
import split.telegram.api.TgChatMember
import split.telegram.api.TgChatMemberUpdated
import split.telegram.api.TgMessage
import split.telegram.api.TgUpdate
import split.telegram.api.TgUser

private const val ALICE_TG_ID = 1L
private const val GROUP_CHAT_ID = -1L
private const val DM_CHAT_ID = 555L
private const val JOINED_GROUP_CHAT_ID = -900L
private val SPLIT_BOT = TgUser(id = 42, firstName = "SplitBot")

private suspend fun duringRouting(block: suspend FakeIdentityResolver.() -> Unit) {
    FakeIdentityResolver().block()
}

private fun anUpdate(text: String) =
    TgUpdate(
        updateId = 1,
        message =
            TgMessage(
                messageId = 1,
                from = TgUser(id = ALICE_TG_ID, firstName = "Alice"),
                chat = TgChat(id = GROUP_CHAT_ID, type = "group"),
                text = text,
            ),
    )

class CommandRouterSpec :
    DescribeSpec({

        describe("group chat commands") {
            describe("dispatches to a handler") {
                it("triggers a recognized command's action, forwarding the remaining text as arguments") {
                    duringRouting {
                        val addInvocations = mutableListOf<CommandContext>()
                        val router =
                            CommandRouter(
                                this,
                                mapOf("add" to { context: CommandContext -> addInvocations += context }),
                            )

                        router.handleUpdate(anUpdate("/add 90 dinner"))

                        addInvocations.single().args shouldBe "90 dinner"
                    }
                }
            }

            describe("building the context") {
                it("resolves the sender's identity and records them as a member of the group") {
                    duringRouting {
                        val helpInvocations = mutableListOf<CommandContext>()
                        val router =
                            CommandRouter(
                                this,
                                mapOf("help" to { context: CommandContext -> helpInvocations += context }),
                            )

                        router.handleUpdate(anUpdate("/help"))

                        val context = helpInvocations.single()
                        context.memberId shouldBe MemberId(ALICE_TG_ID.toString())
                        context.groupId shouldBe GroupId(GROUP_CHAT_ID.toString())
                        groupMemberships shouldContain (context.groupId to context.memberId)
                    }
                }
            }

            describe("ignores") {
                it("does not trigger a different command's action") {
                    duringRouting {
                        val helpInvocations = mutableListOf<CommandContext>()
                        val router =
                            CommandRouter(
                                this,
                                mapOf("help" to { context: CommandContext -> helpInvocations += context }),
                            )

                        router.handleUpdate(anUpdate("/start"))

                        helpInvocations shouldBe emptyList()
                    }
                }

                it("an unrecognized command, creating no identity") {
                    duringRouting {
                        val router = CommandRouter(this, mapOf("help" to { _: CommandContext -> }))

                        router.handleUpdate(anUpdate("/unknown"))

                        resolvedMemberIds shouldBe emptyList()
                    }
                }

                it("plain, non-command chat, creating no identity") {
                    duringRouting {
                        val helpInvocations = mutableListOf<CommandContext>()
                        val router =
                            CommandRouter(
                                this,
                                mapOf("help" to { context: CommandContext -> helpInvocations += context }),
                            )

                        router.handleUpdate(anUpdate("just chatting"))

                        helpInvocations shouldBe emptyList()
                        resolvedMemberIds shouldBe emptyList()
                    }
                }

                it("updates that carry no message or no sender") {
                    duringRouting {
                        val helpInvocations = mutableListOf<CommandContext>()
                        val router =
                            CommandRouter(
                                this,
                                mapOf("help" to { context: CommandContext -> helpInvocations += context }),
                            )

                        router.handleUpdate(TgUpdate(updateId = 1, message = null))

                        helpInvocations shouldBe emptyList()
                    }
                }
            }
        }

        describe("group chat replies (answering an in-flow prompt)") {
            describe("dispatches to a handler") {
                it("treats a reply to a tracked message as input to the flow") {
                    duringRouting {
                        val replies = mutableListOf<ReplyContext>()
                        val reply = ReplyRouting(handler = { replies += it }, isTracked = { _, _ -> true })
                        val router = CommandRouter(this, emptyMap(), reply = reply)

                        router.handleUpdate(
                            TgUpdate(
                                updateId = 1,
                                message =
                                    TgMessage(
                                        messageId = 7,
                                        from = TgUser(id = ALICE_TG_ID, firstName = "Alice"),
                                        chat = TgChat(id = GROUP_CHAT_ID, type = "group"),
                                        text = "50",
                                        replyToMessage =
                                            TgMessage(messageId = 3, chat = TgChat(id = GROUP_CHAT_ID, type = "group")),
                                    ),
                            ),
                        )

                        val context = replies.single()
                        context.chatId shouldBe GROUP_CHAT_ID
                        context.replyToMessageId shouldBe 3
                        context.text shouldBe "50"
                    }
                }
            }

            describe("building the context") {
                it("resolves the sender's identity when dispatching a tracked reply") {
                    duringRouting {
                        val replies = mutableListOf<ReplyContext>()
                        val reply = ReplyRouting(handler = { replies += it }, isTracked = { _, _ -> true })
                        val router = CommandRouter(this, emptyMap(), reply = reply)

                        router.handleUpdate(
                            TgUpdate(
                                updateId = 1,
                                message =
                                    TgMessage(
                                        messageId = 7,
                                        from = TgUser(id = ALICE_TG_ID, firstName = "Alice"),
                                        chat = TgChat(id = GROUP_CHAT_ID, type = "group"),
                                        text = "50",
                                        replyToMessage =
                                            TgMessage(messageId = 3, chat = TgChat(id = GROUP_CHAT_ID, type = "group")),
                                    ),
                            ),
                        )

                        val context = replies.single()
                        context.memberId shouldBe MemberId(ALICE_TG_ID.toString())
                        context.groupId shouldBe GroupId(GROUP_CHAT_ID.toString())
                        groupMemberships shouldContain (context.groupId to context.memberId)
                    }
                }
            }

            describe("ignores") {
                it("a reply to an untracked message, creating no identity") {
                    duringRouting {
                        val replies = mutableListOf<ReplyContext>()
                        val router = CommandRouter(this, emptyMap(), reply = ReplyRouting(handler = { replies += it }))

                        router.handleUpdate(
                            TgUpdate(
                                updateId = 1,
                                message =
                                    TgMessage(
                                        messageId = 7,
                                        from = TgUser(id = ALICE_TG_ID, firstName = "Alice"),
                                        chat = TgChat(id = GROUP_CHAT_ID, type = "group"),
                                        text = "50",
                                        replyToMessage =
                                            TgMessage(messageId = 3, chat = TgChat(id = GROUP_CHAT_ID, type = "group")),
                                    ),
                            ),
                        )

                        replies shouldBe emptyList()
                        resolvedMemberIds shouldBe emptyList()
                    }
                }

                it("a command, even when sent as a reply") {
                    duringRouting {
                        val replies = mutableListOf<ReplyContext>()
                        val helpInvocations = mutableListOf<CommandContext>()
                        val router =
                            CommandRouter(
                                this,
                                mapOf("help" to { context: CommandContext -> helpInvocations += context }),
                                reply = ReplyRouting(handler = { replies += it }),
                            )

                        router.handleUpdate(
                            TgUpdate(
                                updateId = 1,
                                message =
                                    TgMessage(
                                        messageId = 7,
                                        from = TgUser(id = ALICE_TG_ID, firstName = "Alice"),
                                        chat = TgChat(id = GROUP_CHAT_ID, type = "group"),
                                        text = "/help",
                                        replyToMessage =
                                            TgMessage(messageId = 3, chat = TgChat(id = GROUP_CHAT_ID, type = "group")),
                                    ),
                            ),
                        )

                        helpInvocations.size shouldBe 1
                        replies shouldBe emptyList()
                    }
                }

                it("plain text that isn't a reply to anything") {
                    duringRouting {
                        val replies = mutableListOf<ReplyContext>()
                        val router = CommandRouter(this, emptyMap(), reply = ReplyRouting(handler = { replies += it }))

                        router.handleUpdate(anUpdate("just chatting"))

                        replies shouldBe emptyList()
                    }
                }
            }
        }

        describe("group chat inline buttons (callback taps)") {
            describe("dispatches to a handler") {
                it("routes a tap to the general flow when no dedicated action matches") {
                    duringRouting {
                        val received = mutableListOf<CallbackContext>()
                        val callbacks = CallbackRouting(flowHandler = { received += it })
                        val router = CommandRouter(this, emptyMap(), callbacks = callbacks)

                        router.handleUpdate(
                            TgUpdate(
                                updateId = 1,
                                callbackQuery =
                                    TgCallbackQuery(
                                        id = "cbq1",
                                        from = TgUser(id = ALICE_TG_ID, firstName = "Alice"),
                                        message =
                                            TgMessage(
                                                messageId = 42,
                                                chat = TgChat(id = GROUP_CHAT_ID, type = "group"),
                                            ),
                                        data = "split:mode:equal",
                                    ),
                            ),
                        )

                        val context = received.single()
                        context.chatId shouldBe GROUP_CHAT_ID
                        context.callbackQueryId shouldBe "cbq1"
                        context.messageId shouldBe 42L
                        context.data shouldBe "split:mode:equal"
                    }
                }

                it("routes a tap with a dedicated action to that action instead of the general flow") {
                    duringRouting {
                        val staticCallbacks = mutableListOf<CallbackContext>()
                        val flowCallbacks = mutableListOf<CallbackContext>()
                        val helpHandler: CallbackHandler = { context -> staticCallbacks += context }
                        val router =
                            CommandRouter(
                                this,
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
                                        from = TgUser(id = ALICE_TG_ID, firstName = "Alice"),
                                        message =
                                            TgMessage(
                                                messageId = 42,
                                                chat = TgChat(id = GROUP_CHAT_ID, type = "group"),
                                            ),
                                        data = "help",
                                    ),
                            ),
                        )

                        staticCallbacks.single().data shouldBe "help"
                        flowCallbacks shouldBe emptyList()
                    }
                }
            }

            describe("building the context") {
                it("resolves the sender's identity when routing a tap") {
                    duringRouting {
                        val received = mutableListOf<CallbackContext>()
                        val callbacks = CallbackRouting(flowHandler = { received += it })
                        val router = CommandRouter(this, emptyMap(), callbacks = callbacks)

                        router.handleUpdate(
                            TgUpdate(
                                updateId = 1,
                                callbackQuery =
                                    TgCallbackQuery(
                                        id = "cbq1",
                                        from = TgUser(id = ALICE_TG_ID, firstName = "Alice"),
                                        message =
                                            TgMessage(
                                                messageId = 42,
                                                chat = TgChat(id = GROUP_CHAT_ID, type = "group"),
                                            ),
                                        data = "split:mode:equal",
                                    ),
                            ),
                        )

                        val context = received.single()
                        context.memberId shouldBe MemberId(ALICE_TG_ID.toString())
                        context.groupId shouldBe GroupId(GROUP_CHAT_ID.toString())
                        groupMemberships shouldContain (context.groupId to context.memberId)
                    }
                }
            }

            describe("ignores") {
                it("a tap with no underlying message or no payload") {
                    duringRouting {
                        val received = mutableListOf<CallbackContext>()
                        val callbacks = CallbackRouting(flowHandler = { received += it })
                        val router = CommandRouter(this, emptyMap(), callbacks = callbacks)

                        router.handleUpdate(
                            TgUpdate(
                                updateId = 1,
                                callbackQuery =
                                    TgCallbackQuery(
                                        id = "cbq1",
                                        from = TgUser(id = ALICE_TG_ID, firstName = "Alice"),
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
                                        from = TgUser(id = ALICE_TG_ID, firstName = "Alice"),
                                        message =
                                            TgMessage(
                                                messageId = 42,
                                                chat = TgChat(id = GROUP_CHAT_ID, type = "group"),
                                            ),
                                        data = null,
                                    ),
                            ),
                        )

                        received shouldBe emptyList()
                    }
                }
            }
        }

        describe("private chat (DM) commands") {
            describe("dispatches to a handler") {
                it("routes a recognized command to its DM action") {
                    duringRouting {
                        val dmInvocations = mutableListOf<DmCommandContext>()
                        val dmStartHandler: DmCommandHandler = { context -> dmInvocations += context }
                        val router =
                            CommandRouter(
                                this,
                                mapOf("start" to { _: CommandContext -> }),
                                dm = DmRouting(handlers = mapOf("start" to dmStartHandler)),
                            )

                        router.handleUpdate(
                            TgUpdate(
                                updateId = 1,
                                message =
                                    TgMessage(
                                        messageId = 1,
                                        from = TgUser(id = ALICE_TG_ID, firstName = "Alice"),
                                        chat = TgChat(id = DM_CHAT_ID, type = "private"),
                                        text = "/start",
                                    ),
                            ),
                        )

                        dmInvocations.single().chatId shouldBe DM_CHAT_ID
                    }
                }

                it("falls back to the discovery flow for an unrecognized command") {
                    duringRouting {
                        val fallbackInvocations = mutableListOf<DmCommandContext>()
                        val router =
                            CommandRouter(
                                this,
                                emptyMap(),
                                dm = DmRouting(fallbackHandler = { context -> fallbackInvocations += context }),
                            )

                        router.handleUpdate(
                            TgUpdate(
                                updateId = 1,
                                message =
                                    TgMessage(
                                        messageId = 1,
                                        from = TgUser(id = ALICE_TG_ID, firstName = "Alice"),
                                        chat = TgChat(id = DM_CHAT_ID, type = "private"),
                                        text = "/split",
                                    ),
                            ),
                        )

                        fallbackInvocations.single().args shouldBe ""
                    }
                }

                it("falls back to the discovery flow for plain text") {
                    duringRouting {
                        val fallbackInvocations = mutableListOf<DmCommandContext>()
                        val router =
                            CommandRouter(
                                this,
                                emptyMap(),
                                dm = DmRouting(fallbackHandler = { context -> fallbackInvocations += context }),
                            )

                        router.handleUpdate(
                            TgUpdate(
                                updateId = 1,
                                message =
                                    TgMessage(
                                        messageId = 1,
                                        from = TgUser(id = ALICE_TG_ID, firstName = "Alice"),
                                        chat = TgChat(id = DM_CHAT_ID, type = "private"),
                                        text = "hi there",
                                    ),
                            ),
                        )

                        fallbackInvocations.single().chatId shouldBe DM_CHAT_ID
                    }
                }
            }

            describe("building the context") {
                it("resolves the sender's identity via a DM command, without resolving a group") {
                    duringRouting {
                        val dmInvocations = mutableListOf<DmCommandContext>()
                        val dmStartHandler: DmCommandHandler = { context -> dmInvocations += context }
                        val router =
                            CommandRouter(
                                this,
                                mapOf("start" to { _: CommandContext -> }),
                                dm = DmRouting(handlers = mapOf("start" to dmStartHandler)),
                            )

                        router.handleUpdate(
                            TgUpdate(
                                updateId = 1,
                                message =
                                    TgMessage(
                                        messageId = 1,
                                        from = TgUser(id = ALICE_TG_ID, firstName = "Alice"),
                                        chat = TgChat(id = DM_CHAT_ID, type = "private"),
                                        text = "/start",
                                    ),
                            ),
                        )

                        dmInvocations.single().memberId shouldBe MemberId(ALICE_TG_ID.toString())
                        resolvedGroupIds shouldBe emptyList()
                    }
                }
            }

            describe("ignores") {
                it("an unrecognized command when no fallback is configured, creating no identity") {
                    duringRouting {
                        val router = CommandRouter(this, emptyMap())

                        router.handleUpdate(
                            TgUpdate(
                                updateId = 1,
                                message =
                                    TgMessage(
                                        messageId = 1,
                                        from = TgUser(id = ALICE_TG_ID, firstName = "Alice"),
                                        chat = TgChat(id = DM_CHAT_ID, type = "private"),
                                        text = "/split",
                                    ),
                            ),
                        )

                        resolvedMemberIds shouldBe emptyList()
                    }
                }
            }
        }

        describe("the bot's own group membership changing") {
            describe("dispatches to a handler") {
                it("being added to a group is treated as joining") {
                    duringRouting {
                        val joinInvocations = mutableListOf<GroupJoinContext>()
                        val router = CommandRouter(this, emptyMap(), groupJoinHandler = { joinInvocations += it })

                        router.handleUpdate(
                            TgUpdate(
                                updateId = 1,
                                myChatMember =
                                    TgChatMemberUpdated(
                                        chat = TgChat(id = JOINED_GROUP_CHAT_ID, type = "supergroup"),
                                        oldChatMember = TgChatMember(status = "left", user = SPLIT_BOT),
                                        newChatMember = TgChatMember(status = "member", user = SPLIT_BOT),
                                    ),
                            ),
                        )

                        joinInvocations.single().chatId shouldBe JOINED_GROUP_CHAT_ID
                    }
                }
            }

            describe("building the context") {
                it("resolves a group identity for the chat that was joined") {
                    duringRouting {
                        val joinInvocations = mutableListOf<GroupJoinContext>()
                        val router = CommandRouter(this, emptyMap(), groupJoinHandler = { joinInvocations += it })

                        router.handleUpdate(
                            TgUpdate(
                                updateId = 1,
                                myChatMember =
                                    TgChatMemberUpdated(
                                        chat = TgChat(id = JOINED_GROUP_CHAT_ID, type = "supergroup"),
                                        oldChatMember = TgChatMember(status = "left", user = SPLIT_BOT),
                                        newChatMember = TgChatMember(status = "member", user = SPLIT_BOT),
                                    ),
                            ),
                        )

                        joinInvocations.single().groupId shouldBe GroupId(JOINED_GROUP_CHAT_ID.toString())
                    }
                }
            }

            describe("ignores") {
                it("being added to a channel — that's not joining a group") {
                    duringRouting {
                        val joinInvocations = mutableListOf<GroupJoinContext>()
                        val router = CommandRouter(this, emptyMap(), groupJoinHandler = { joinInvocations += it })

                        router.handleUpdate(
                            TgUpdate(
                                updateId = 1,
                                myChatMember =
                                    TgChatMemberUpdated(
                                        chat = TgChat(id = JOINED_GROUP_CHAT_ID, type = "channel"),
                                        oldChatMember = TgChatMember(status = "left", user = SPLIT_BOT),
                                        newChatMember = TgChatMember(status = "administrator", user = SPLIT_BOT),
                                    ),
                            ),
                        )

                        joinInvocations shouldBe emptyList()
                    }
                }

                it("being removed from a group — that's not joining one") {
                    duringRouting {
                        val joinInvocations = mutableListOf<GroupJoinContext>()
                        val router = CommandRouter(this, emptyMap(), groupJoinHandler = { joinInvocations += it })

                        router.handleUpdate(
                            TgUpdate(
                                updateId = 1,
                                myChatMember =
                                    TgChatMemberUpdated(
                                        chat = TgChat(id = JOINED_GROUP_CHAT_ID, type = "supergroup"),
                                        oldChatMember = TgChatMember(status = "member", user = SPLIT_BOT),
                                        newChatMember = TgChatMember(status = "left", user = SPLIT_BOT),
                                    ),
                            ),
                        )

                        joinInvocations shouldBe emptyList()
                    }
                }

                it("a user starting the bot in a private chat — that's not joining a group") {
                    duringRouting {
                        val joinInvocations = mutableListOf<GroupJoinContext>()
                        val router = CommandRouter(this, emptyMap(), groupJoinHandler = { joinInvocations += it })

                        router.handleUpdate(
                            TgUpdate(
                                updateId = 1,
                                myChatMember =
                                    TgChatMemberUpdated(
                                        chat = TgChat(id = DM_CHAT_ID, type = "private"),
                                        oldChatMember = TgChatMember(status = "left", user = SPLIT_BOT),
                                        newChatMember = TgChatMember(status = "member", user = SPLIT_BOT),
                                    ),
                            ),
                        )

                        joinInvocations shouldBe emptyList()
                    }
                }
            }
        }
    })
