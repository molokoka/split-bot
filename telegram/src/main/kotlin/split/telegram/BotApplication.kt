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
        val updates = try {
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
    val flowStore = SplitFlowStore()
    val draftStore = SplitDraftStore()
    val flowStarter = SplitFlowStarter(flowStore, memberRepository, platformDirectory, expenseRepository, telegramApi)
    val splitFlowCallbackHandler = SplitFlowCallbackHandler(
        flowStore, groupRepository, memberRepository, expenseRepository, platformDirectory, telegramApi,
    )
    val splitFlowReplyHandler = SplitFlowReplyHandler(flowStore, memberRepository, platformDirectory, telegramApi)
    val splitDraftReplyHandler = SplitDraftReplyHandler(draftStore, platformDirectory, telegramApi, flowStarter)

    val handlers = mapOf(
        "start" to StartCommand(groupRepository, telegramApi)::handle,
        "help" to HelpCommand(telegramApi)::handle,
        "currency" to CurrencyCommand(groupRepository, telegramApi)::handle,
        "members" to MembersCommand(memberRepository, platformDirectory, telegramApi)::handle,
        "split" to SplitExpenseCommand(
            platformDirectory, groupRepository, identityResolver, telegramApi, draftStore, flowStarter,
        )::handle,
        "delete" to DeleteExpenseCommand(groupRepository, expenseRepository, telegramApi)::handle,
        "expenses" to ExpensesCommand(groupRepository, memberRepository, expenseRepository, platformDirectory, telegramApi)::handle,
        "balance" to BalanceCommand(
            groupRepository, memberRepository, expenseRepository, settlementRepository, platformDirectory, telegramApi,
        )::handle,
        "settle" to SettleCommand(platformDirectory, groupRepository, settlementRepository, telegramApi)::handle,
        "settle_suggest" to SettleSuggestCommand(
            groupRepository, memberRepository, expenseRepository, settlementRepository, platformDirectory, telegramApi,
        )::handle,
        "settlements" to SettlementsCommand(groupRepository, memberRepository, settlementRepository, platformDirectory, telegramApi)::handle,
        "history" to HistoryCommand(
            groupRepository, memberRepository, expenseRepository, settlementRepository, platformDirectory, telegramApi,
        )::handle,
    )

    val router = CommandRouter(
        identityResolver,
        handlers,
        callbackHandler = splitFlowCallbackHandler::handle,
        replyHandler = { context ->
            if (draftStore.get(context.chatId)?.promptMessageId == context.replyToMessageId) {
                splitDraftReplyHandler.handle(context)
            } else {
                splitFlowReplyHandler.handle(context)
            }
        },
        isTrackedReply = { chatId, messageId ->
            draftStore.get(chatId)?.promptMessageId == messageId ||
                flowStore.get(chatId)?.let {
                    it.promptMessageId == messageId || it.actionsMessageId == messageId || it.pendingPromptMessageId == messageId
                } ?: false
        },
    )
    val pollLoop = PollLoop(telegramApi, router)

    println("Bot started, polling for updates...")
    while (true) {
        pollLoop.pollOnce()
    }
}
