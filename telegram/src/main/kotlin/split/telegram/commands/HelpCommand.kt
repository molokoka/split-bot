package split.telegram.commands

import split.core.GroupRepository
import split.core.MemberRepository
import split.core.PlatformDirectory
import split.telegram.CallbackContext
import split.telegram.CommandContext
import split.telegram.GroupJoinContext
import split.telegram.IdentityResolver
import split.telegram.api.BotCommand
import split.telegram.api.InlineKeyboardButton
import split.telegram.api.InlineKeyboardMarkup
import split.telegram.api.TelegramApi
import split.telegram.memberRoster
import split.telegram.mentionName

// Sent with parse_mode HTML (see HttpTelegramApi), so placeholders use <code>...</code>
// rather than bare <angle brackets> — a raw "<amount>" would be read as an (invalid,
// unclosed) HTML tag and mangle or break the message. The @-prefixed placeholders
// (@mentions, @person) are wrapped in <code> too, for a different reason: Telegram
// can't nest other entities inside code/pre, so this is also what stops it from reading
// literal example text like "@person" as a real mention and pinging whichever account
// happens to hold that username.
internal const val HELP_TEXT = """🎯 Essential
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
/expenses — last 10 expenses
/expenses pending — splits still waiting on someone's amount
/delete <code>id</code> — remove an expense (payer or admin only)
/settle <code>@person</code> <code>amount</code> — record that you paid them
/settle_suggest — minimal set of payments to settle the group up
/settlements — last 10 recorded settlements
/history — each member's balance after the last 20 splits and settlements
/currency <code>currency</code> — set this group's default currency

To mention someone in <code>/split</code> or <code>/settle</code>, they need to have sent me /start at least once."""

internal const val COMMANDS_TEXT_BASIC = """🎯 Essential
/split — log an expense (tap 🧾 Split below, or just run /split and I'll walk you through it)
/balance — see who owes you and who you owe
/settle <code>@person</code> <code>amount</code> — record that you paid them (or tap 🤝 Settle below)
/members — list who I recognize in this group
/commands — this message

To mention someone in <code>/split</code> or <code>/settle</code>, they need to have sent me /start at least once."""

internal const val COMMANDS_TEXT_ADVANCED = """⚙️ Advanced
One-line /split — skip the guided prompts and enter everything at once:
<code>/split 90 dinner @alice @bob</code> — pick Equal or Exact after
<code>/split equal 90 dinner @alice @bob</code> — split equally, no tap needed
<code>/split exact 90 dinner @alice 50 @bob 40</code> — exact amounts, right in the command
<code>/split exact 90 dinner @alice @bob</code> — exact split, enter amounts by replying
(currency defaults to the group's if omitted)

/balances — see every member's balance
/expenses — last 10 expenses
/expenses pending — splits still waiting on someone's amount
/delete <code>id</code> — remove an expense (payer or admin only)
/settle_suggest — minimal set of payments to settle the group up
/settlements — last 10 recorded settlements
/history — each member's balance after the last 20 splits and settlements
/currency <code>currency</code> — set this group's default currency"""

internal const val COMMANDS_CALLBACK_DATA = "commands"
internal const val COMMANDS_ADVANCED_CALLBACK_DATA = "commands_advanced"
internal const val COMMANDS_SPLIT_CALLBACK_DATA = "commands_split"
internal const val COMMANDS_BALANCE_CALLBACK_DATA = "commands_balance"
internal const val COMMANDS_SETTLE_CALLBACK_DATA = "commands_settle"

internal val BOT_COMMANDS =
    listOf(
        BotCommand("start", "Start using the bot in this group"),
        BotCommand("commands", "Show commands"),
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
        BotCommand("help", "Show every command in one message"),
    )

private fun commandsEntryKeyboard(): InlineKeyboardMarkup =
    InlineKeyboardMarkup(
        inlineKeyboard =
            listOf(
                listOf(InlineKeyboardButton(text = "📋 Commands", callbackData = COMMANDS_CALLBACK_DATA)),
            ),
    )

private fun commandsButtonKeyboard(): InlineKeyboardMarkup =
    InlineKeyboardMarkup(
        inlineKeyboard =
            listOf(
                listOf(
                    InlineKeyboardButton(text = "🧾 Split", callbackData = COMMANDS_SPLIT_CALLBACK_DATA),
                    InlineKeyboardButton(text = "💰 Balance", callbackData = COMMANDS_BALANCE_CALLBACK_DATA),
                    InlineKeyboardButton(text = "🤝 Settle", callbackData = COMMANDS_SETTLE_CALLBACK_DATA),
                ),
                listOf(InlineKeyboardButton(text = "⚙️ Advanced", callbackData = COMMANDS_ADVANCED_CALLBACK_DATA)),
            ),
    )

// CallbackContext carries no externalUserId (Telegram callback taps don't need one); the command
// handlers this builds a context for never read that field, so an empty placeholder is safe.
private fun CallbackContext.toCommandContext() =
    CommandContext(
        chatId,
        memberId,
        externalUserId = "",
        groupId,
        args = "",
    )

class CommandsCommand(
    private val telegramApi: TelegramApi,
    private val balanceCommand: BalanceCommand,
    private val settleCommand: SettleCommand,
    private val splitExpenseCommand: SplitExpenseCommand,
) {
    suspend fun handle(context: CommandContext) {
        telegramApi.sendMessage(context.chatId, COMMANDS_TEXT_BASIC, commandsButtonKeyboard())
    }

    suspend fun handleWelcomeCallback(context: CallbackContext) {
        telegramApi.sendMessage(context.chatId, COMMANDS_TEXT_BASIC, commandsButtonKeyboard())
        telegramApi.answerCallbackQuery(context.callbackQueryId)
    }

    suspend fun handleAdvancedCallback(context: CallbackContext) {
        telegramApi.sendMessage(context.chatId, COMMANDS_TEXT_ADVANCED)
        telegramApi.answerCallbackQuery(context.callbackQueryId)
    }

    suspend fun handleSplitCallback(context: CallbackContext) {
        splitExpenseCommand.handle(context.toCommandContext())
        telegramApi.answerCallbackQuery(context.callbackQueryId)
    }

    suspend fun handleBalanceCallback(context: CallbackContext) {
        balanceCommand.handle(context.toCommandContext())
        telegramApi.answerCallbackQuery(context.callbackQueryId)
    }

    suspend fun handleSettleCallback(context: CallbackContext) {
        settleCommand.handle(context.toCommandContext())
        telegramApi.answerCallbackQuery(context.callbackQueryId)
    }
}

private const val GROUP_JOIN_START_RULE =
    "One thing first: everyone who'll be <code>@mentioned</code> in /split needs to send me /start — " +
        "type /start here to register yourself.\n\n"

private fun groupWelcomeText(defaultCurrency: String): String =
    "👋 Hi! I split expenses for this group.\n\n" +
        GROUP_JOIN_START_RULE +
        "This group's default currency is $defaultCurrency — change it anytime with /currency.\n\n" +
        "Tap below to see everything I can do."

class HelpCommand(
    private val telegramApi: TelegramApi,
) {
    suspend fun handle(context: CommandContext) {
        telegramApi.sendMessage(context.chatId, HELP_TEXT)
    }
}

class StartCommand(
    private val groupRepository: GroupRepository,
    private val memberRepository: MemberRepository,
    private val platformDirectory: PlatformDirectory,
    private val telegramApi: TelegramApi,
) {
    // The full welcome (currency, mention rule, commands button) is delivered once, reliably,
    // by welcomeNewGroup when the bot joins — repeating it here on every /start is what caused
    // the double/triple-message pile-up this replaced. A registered member just needs to know
    // they're recognized now, plus who else is already registered.
    suspend fun handle(context: CommandContext) {
        val members = memberRepository.findByGroup(context.groupId)
        val usernames = platformDirectory.findUsernames(IdentityResolver.PLATFORM, members.map { it.id })
        val caller = members.find { it.id == context.memberId }
        val greeting = caller?.let { ", ${mentionName(it, usernames)}" } ?: ""
        val message =
            if (members.size < 2) {
                "✅ You're registered$greeting. Nobody else in the group has registered yet — " +
                    "ask everyone to run /start too."
            } else {
                val confirmation =
                    "✅ You're registered$greeting — I'll recognize you when you're <code>@mentioned</code> in /split."
                "$confirmation\n\nHere's who's registered already: ${memberRoster(members, usernames)}\n" +
                    "— /members to check out members."
            }
        telegramApi.sendMessage(context.chatId, message)
    }

    suspend fun welcomeNewGroup(context: GroupJoinContext) {
        val group = groupRepository.find(context.groupId) ?: error("Group ${context.groupId} not found")
        telegramApi.sendMessage(context.chatId, groupWelcomeText(group.defaultCurrency), commandsEntryKeyboard())
    }
}
