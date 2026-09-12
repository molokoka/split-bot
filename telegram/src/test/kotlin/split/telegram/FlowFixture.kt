package split.telegram

import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.jdbc.Database
import split.core.GroupId
import split.core.MemberId
import split.storage.ExposedExpenseRepository
import split.storage.ExposedGroupRepository
import split.storage.ExposedMemberRepository
import split.storage.ExposedPlatformDirectory
import split.storage.ExposedSplitFlowStateRepository
import split.telegram.commands.ExpensesCommand
import split.telegram.commands.SplitExpenseCommand
import split.telegram.splitflow.PendingSplit
import split.telegram.splitflow.SplitFlowCallbackHandler
import split.telegram.splitflow.SplitFlowReplyHandler
import split.telegram.splitflow.SplitFlowStarter
import split.telegram.splitflow.SplitStateStore
import java.math.BigDecimal

internal const val CHAT_ID = -100L

/**
 * One group chat with the real handlers wired to a real database, driven the way people drive it:
 * someone types a command, taps a button by the label they can see, or replies to a prompt. Assert
 * with [chat] to check what the chat now looks like.
 */
internal class FlowFixture(
    db: Database,
) {
    val platformDirectory = ExposedPlatformDirectory(db)
    val groupRepository = ExposedGroupRepository(db)
    val memberRepository = ExposedMemberRepository(db)
    val expenseRepository = ExposedExpenseRepository(db)
    val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)
    val telegramApi = FakeTelegramApi()
    val splitStateStore = SplitStateStore(ExposedSplitFlowStateRepository(db))
    val flowStarter =
        SplitFlowStarter(splitStateStore, memberRepository, platformDirectory, expenseRepository, telegramApi)
    val splitCommand =
        SplitExpenseCommand(
            platformDirectory,
            groupRepository,
            memberRepository,
            resolver,
            telegramApi,
            splitStateStore,
            flowStarter,
        )
    val expensesCommand =
        ExpensesCommand(
            groupRepository,
            memberRepository,
            expenseRepository,
            platformDirectory,
            telegramApi,
            splitStateStore,
        )
    val callbackHandler =
        SplitFlowCallbackHandler(splitStateStore, memberRepository, expenseRepository, platformDirectory, telegramApi)
    val replyHandler = SplitFlowReplyHandler(splitStateStore, memberRepository, platformDirectory, telegramApi)

    private var resolvedGroupId: GroupId? = null

    /** The group this chat is linked to, once [aliceAndBobbyInGroup] has set the chat up. */
    var groupId: GroupId
        get() = resolvedGroupId ?: error("Set the chat up first, e.g. with aliceAndBobbyInGroup().")
        set(value) {
            resolvedGroupId = value
        }

    private val memberIds = mutableMapOf<String, MemberId>()
    private val externalIds = mutableMapOf<String, String>()

    fun idOf(actor: String): MemberId = memberIds.getValue(actor)

    internal fun register(
        actor: String,
        memberId: MemberId,
        externalId: String,
    ) {
        memberIds[actor] = memberId
        externalIds[actor] = externalId
    }

    internal fun externalIdOf(actor: String): String = externalIds.getValue(actor)
}

/**
 * Alice and Bobby in one group, Alice being the one who talks to the bot first. Mirrors what the
 * bot knows after both have used it: display names, and @usernames the formatter prefers.
 */
internal suspend fun FlowFixture.aliceAndBobbyInGroup() {
    val aliceId = resolver.resolveMember("1", "alice", "Alice")
    groupId = resolver.resolveGroup("-100")
    resolver.ensureGroupMembership(groupId, aliceId)
    val bobbyId = resolver.resolveMember("2", "bobby", "Bob")
    register("alice", aliceId, "1")
    register("bobby", bobbyId, "2")
}

/** What the chat looks like now. [tail] keeps only the last n entries, for a focused assertion. */
internal fun FlowFixture.chat(tail: Int? = null): String = renderChat(telegramApi.events, tail)

internal suspend fun FlowFixture.split(
    actor: String,
    args: String,
) {
    telegramApi.userSaid(actor, "/split $args")
    splitCommand.handle(CommandContext(CHAT_ID, idOf(actor), externalIdOf(actor), groupId, args))
}

internal suspend fun FlowFixture.expensesPending(actor: String) {
    telegramApi.userSaid(actor, "/expenses pending")
    expensesCommand.handle(CommandContext(CHAT_ID, idOf(actor), externalIdOf(actor), groupId, "pending"))
}

/**
 * Taps the button labelled [label]. When two open flows show the same label — both have a Cancel —
 * the test must say which message it means via [onMessage], rather than silently getting one of them.
 */
internal suspend fun FlowFixture.tap(
    actor: String,
    label: String,
    onMessage: Long? = null,
) {
    val matches = visibleButtons(telegramApi.events, label)
    val (messageId, callbackData) =
        when {
            onMessage != null ->
                matches.firstOrNull { it.first == onMessage }
                    ?: error("No button labelled \"$label\" on message #$onMessage. Chat:\n${chat()}")
            matches.isEmpty() -> error("No button labelled \"$label\" in the chat. Chat:\n${chat()}")
            matches.size > 1 ->
                error(
                    "\"$label\" appears on messages ${matches.map { "#${it.first}" }} — " +
                        "pass onMessage to say which one. Chat:\n${chat()}",
                )
            else -> matches.single()
        }
    telegramApi.userTapped(actor, messageId, callbackData)
    callbackHandler.handle(CallbackContext(CHAT_ID, idOf(actor), groupId, "cbq", messageId, callbackData))
}

/** Taps a button that is no longer (or was never) in the chat — a stale tap from an old message. */
internal suspend fun FlowFixture.tapStale(
    actor: String,
    messageId: Long,
    callbackData: String,
) {
    telegramApi.userTapped(actor, messageId, callbackData)
    callbackHandler.handle(CallbackContext(CHAT_ID, idOf(actor), groupId, "cbq", messageId, callbackData))
}

/** Replies to the newest force-reply prompt still in the chat, as a person tapping "reply" would. */
internal suspend fun FlowFixture.replyToPrompt(
    actor: String,
    text: String,
) {
    val promptMessageId =
        newestForceReplyPrompt(telegramApi.events)
            ?: error("There is no prompt to reply to. Chat:\n${chat()}")
    replyTo(actor, promptMessageId, text)
}

internal suspend fun FlowFixture.replyTo(
    actor: String,
    messageId: Long,
    text: String,
) {
    telegramApi.userSaid(actor, text, replyToMessageId = messageId)
    replyHandler.handle(ReplyContext(CHAT_ID, idOf(actor), groupId, messageId, text))
}

/** The single open split in this chat, or null once it's been confirmed or cancelled. */
internal suspend fun FlowFixture.currentFlow(): PendingSplit? =
    splitStateStore.listAll(CHAT_ID).filterIsInstance<PendingSplit>().singleOrNull()

internal suspend fun FlowFixture.flowFor(description: String): PendingSplit? =
    splitStateStore
        .listAll(CHAT_ID)
        .filterIsInstance<PendingSplit>()
        .singleOrNull { it.description == description }

internal suspend fun FlowFixture.expenseCreatedWith(vararg shares: Pair<String, BigDecimal>) {
    val expense = expenseRepository.listActive(groupId).single()
    expense.shares.associate { it.memberId to it.shareAmount } shouldBe
        shares.associate { (actor, amount) -> idOf(actor) to amount }
}
