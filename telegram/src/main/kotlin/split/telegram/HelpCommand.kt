package split.telegram

import split.core.GroupId
import split.core.GroupRepository

// Sent with parse_mode HTML (see HttpTelegramApi), so placeholders use <code>...</code>
// rather than bare <angle brackets> — a raw "<amount>" would be read as an (invalid,
// unclosed) HTML tag and mangle or break the message. The @-prefixed placeholders
// (@mentions, @person) are wrapped in <code> too, for a different reason: Telegram
// can't nest other entities inside code/pre, so this is also what stops it from reading
// literal example text like "@person" as a real mention and pinging whichever account
// happens to hold that username.
internal const val HELP_TEXT = """Commands:

🎯 Essential
/split — log an expense you paid. A few ways to use it:
<code>/split 90 dinner @alice @bob</code> — pick Equal or Exact after
<code>/split equal 90 dinner @alice @bob</code> — split equally, no tap needed
<code>/split exact 90 dinner @alice 50 @bob 40</code> — exact amounts, right in the command
<code>/split exact 90 dinner @alice @bob</code> — exact split, enter amounts by replying
<code>/split</code> or <code>/split dinner</code> — leave anything out and I'll ask for it
(currency defaults to the group's if omitted)
/balance — see who owes you and who you owe
/members — list who I recognize in this group
/help — this message

⚙️ Advanced
/balances — see every member's balance
/settle <code>@person</code> <code>amount</code> — record that you paid them
/settle_suggest — minimal set of payments to settle the group up
/settlements — last 10 recorded settlements
/expenses — last 10 expenses
/delete <code>id</code> — remove an expense (payer or admin only)
/history — each member's balance after the last 20 splits and settlements
/currency <code>currency</code> — set this group's default currency

To mention someone in <code>/split</code> or <code>/settle</code>, they need to have sent me /start at least once."""

internal const val HELP_CALLBACK_DATA = "help"

internal val BOT_COMMANDS =
    listOf(
        BotCommand("start", "Start using the bot in this group"),
        BotCommand("split", "Log an expense and split it"),
        BotCommand("balance", "See who owes you and who you owe"),
        BotCommand("members", "List who I recognize in this group"),
        BotCommand("balances", "See every member's balance"),
        BotCommand("settle", "Record a payment you made"),
        BotCommand("settle_suggest", "Minimal payments to settle the group up"),
        BotCommand("settlements", "Last 10 recorded settlements"),
        BotCommand("expenses", "Last 10 expenses"),
        BotCommand("delete", "Remove an expense"),
        BotCommand("history", "Balance history after recent activity"),
        BotCommand("currency", "Set this group's default currency"),
        BotCommand("help", "Show all commands"),
    )

private fun helpButtonKeyboard(): InlineKeyboardMarkup =
    InlineKeyboardMarkup(
        inlineKeyboard =
            listOf(
                listOf(InlineKeyboardButton(text = "📋 All commands", callbackData = HELP_CALLBACK_DATA)),
            ),
    )

private fun groupWelcomeText(defaultCurrency: String): String =
    "👋 Hi! I split expenses for this group.\n\n" +
        "One thing first: everyone who'll be @mentioned in /split needs to send me /start too — " +
        "including you, just now. ✅\n\n" +
        "This group's default currency is $defaultCurrency — change it anytime with /currency.\n\n" +
        "Tap below to see everything I can do."

class HelpCommand(
    private val telegramApi: TelegramApi,
) {
    suspend fun handle(context: CommandContext) {
        telegramApi.sendMessage(context.chatId, HELP_TEXT)
    }

    suspend fun handleCallback(context: CallbackContext) {
        telegramApi.sendMessage(context.chatId, HELP_TEXT)
        telegramApi.answerCallbackQuery(context.callbackQueryId)
    }
}

class StartCommand(
    private val groupRepository: GroupRepository,
    private val telegramApi: TelegramApi,
) {
    suspend fun handle(context: CommandContext) = welcome(context.chatId, context.groupId)

    suspend fun welcomeNewGroup(context: GroupJoinContext) = welcome(context.chatId, context.groupId)

    private suspend fun welcome(
        chatId: Long,
        groupId: GroupId,
    ) {
        val group = groupRepository.find(groupId) ?: error("Group $groupId not found")
        telegramApi.sendMessage(chatId, groupWelcomeText(group.defaultCurrency), helpButtonKeyboard())
    }
}
