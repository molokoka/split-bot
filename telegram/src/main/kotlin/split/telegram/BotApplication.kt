package split.telegram

import split.storage.ExposedExpenseRepository
import split.storage.ExposedGroupRepository
import split.storage.ExposedMemberRepository
import split.storage.ExposedPlatformDirectory
import split.storage.ExposedSettlementRepository
import split.storage.ExposedSplitFlowStateRepository
import split.storage.connectDatabaseFromEnv
import split.telegram.api.HttpTelegramApi
import split.telegram.api.TelegramApi
import split.telegram.commands.BalanceCommand
import split.telegram.commands.BalancesCommand
import split.telegram.commands.CurrencyCommand
import split.telegram.commands.DeleteExpenseCommand
import split.telegram.commands.ExpensesCommand
import split.telegram.commands.HelpCommand
import split.telegram.commands.HistoryCommand
import split.telegram.commands.MembersCommand
import split.telegram.commands.SettleCommand
import split.telegram.commands.SettleSuggestCommand
import split.telegram.commands.SettlementsCommand
import split.telegram.commands.SplitExpenseCommand
import split.telegram.commands.StartCommand
import split.telegram.splitflow.PendingSplit
import split.telegram.splitflow.PendingSplitDraft
import split.telegram.splitflow.SplitDraftReplyHandler
import split.telegram.splitflow.SplitFlowCallbackHandler
import split.telegram.splitflow.SplitFlowReplyHandler
import split.telegram.splitflow.SplitFlowStarter
import split.telegram.splitflow.SplitStateStore

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
                e.printStackTrace()
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
    val splitStateStore = SplitStateStore(ExposedSplitFlowStateRepository(db))
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
            "expenses" to ExpensesCommand(
                groupRepository,
                memberRepository,
                expenseRepository,
                platformDirectory,
                telegramApi,
                splitStateStore,
            )::handle,
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
                when (splitStateStore.find(context.chatId, context.replyToMessageId)) {
                    is PendingSplitDraft -> splitDraftReplyHandler.handle(context)
                    is PendingSplit -> splitFlowReplyHandler.handle(context)
                    null -> {}
                }
            },
            isTrackedReply = { chatId, messageId ->
                splitStateStore.find(chatId, messageId) != null
            },
        )
    val pollLoop = PollLoop(telegramApi, router)

    println("Bot started, polling for updates...")
    while (true) {
        pollLoop.pollOnce()
    }
}
