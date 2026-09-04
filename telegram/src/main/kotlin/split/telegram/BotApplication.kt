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
        val updates =
            try {
                telegramApi.getUpdates(offset, timeoutSeconds)
            } catch (e: Exception) {
                System.err.println("Error fetching updates: ${e.message}")
                emptyList()
            }
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
    val botToken =
        System.getenv("TELEGRAM_BOT_TOKEN")
            ?: error("TELEGRAM_BOT_TOKEN environment variable is required")

    val db = connectDatabaseFromEnv()
    val memberRepository = ExposedMemberRepository(db)
    val groupRepository = ExposedGroupRepository(db)
    val expenseRepository = ExposedExpenseRepository(db)
    val settlementRepository = ExposedSettlementRepository(db)
    val platformDirectory = ExposedPlatformDirectory(db)

    val telegramApi = HttpTelegramApi(botToken)
    val identityResolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)
    val splitStateStore = SplitStateStore()
    val flowStarter = SplitFlowStarter(splitStateStore, memberRepository, platformDirectory, expenseRepository, telegramApi)
    val splitFlowCallbackHandler =
        SplitFlowCallbackHandler(
            splitStateStore,
            memberRepository,
            expenseRepository,
            platformDirectory,
            telegramApi,
        )
    val splitFlowReplyHandler = SplitFlowReplyHandler(splitStateStore, memberRepository, platformDirectory, telegramApi)
    val splitDraftReplyHandler = SplitDraftReplyHandler(splitStateStore, memberRepository, platformDirectory, telegramApi, flowStarter)

    val handlers =
        mapOf(
            "start" to StartCommand(groupRepository, telegramApi)::handle,
            "help" to HelpCommand(telegramApi)::handle,
            "currency" to CurrencyCommand(groupRepository, telegramApi)::handle,
            "members" to MembersCommand(memberRepository, platformDirectory, telegramApi)::handle,
            "split" to SplitExpenseCommand(
                platformDirectory,
                groupRepository,
                memberRepository,
                identityResolver,
                telegramApi,
                splitStateStore,
                flowStarter,
            )::handle,
            "delete" to DeleteExpenseCommand(groupRepository, expenseRepository, telegramApi)::handle,
            "expenses" to ExpensesCommand(groupRepository, memberRepository, expenseRepository, platformDirectory, telegramApi)::handle,
            "balance" to BalanceCommand(
                groupRepository,
                memberRepository,
                expenseRepository,
                settlementRepository,
                platformDirectory,
                telegramApi,
            )::handle,
            "balances" to BalancesCommand(
                groupRepository,
                memberRepository,
                expenseRepository,
                settlementRepository,
                platformDirectory,
                telegramApi,
            )::handle,
            "settle" to SettleCommand(platformDirectory, groupRepository, settlementRepository, telegramApi)::handle,
            "settle_suggest" to SettleSuggestCommand(
                groupRepository,
                memberRepository,
                expenseRepository,
                settlementRepository,
                platformDirectory,
                telegramApi,
            )::handle,
            "settlements" to SettlementsCommand(
                groupRepository,
                memberRepository,
                settlementRepository,
                platformDirectory,
                telegramApi,
            )::handle,
            "history" to HistoryCommand(
                groupRepository,
                memberRepository,
                expenseRepository,
                settlementRepository,
                platformDirectory,
                telegramApi,
            )::handle,
        )

    val router =
        CommandRouter(
            identityResolver,
            handlers,
            callbackHandler = splitFlowCallbackHandler::handle,
            replyHandler = { context ->
                when (splitStateStore.get(context.chatId)) {
                    is PendingSplitDraft -> splitDraftReplyHandler.handle(context)
                    is PendingSplit -> splitFlowReplyHandler.handle(context)
                    null -> {}
                }
            },
            isTrackedReply = { chatId, messageId ->
                when (val state = splitStateStore.get(chatId)) {
                    is PendingSplitDraft -> state.promptMessageId == messageId
                    is PendingSplit ->
                        state.promptMessageId == messageId ||
                            state.actionsMessageId == messageId ||
                            state.pendingPromptMessageId == messageId
                    null -> false
                }
            },
        )
    val pollLoop = PollLoop(telegramApi, router)

    println("Bot started, polling for updates...")
    while (true) {
        pollLoop.pollOnce()
    }
}
