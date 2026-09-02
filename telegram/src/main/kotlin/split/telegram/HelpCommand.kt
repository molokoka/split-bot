package split.telegram

import split.core.GroupRepository

// Sent with parse_mode HTML (see HttpTelegramApi), so placeholders use <code>...</code>
// rather than bare <angle brackets> — a raw "<amount>" would be read as an (invalid,
// unclosed) HTML tag and mangle or break the message. The @-prefixed placeholders
// (@mentions, @person) are wrapped in <code> too, for a different reason: Telegram
// can't nest other entities inside code/pre, so this is also what stops it from reading
// literal example text like "@person" as a real mention and pinging whichever account
// happens to hold that username.
internal const val HELP_TEXT = """Commands:

/split <code>amount</code> [<code>currency</code>] <code>description</code> <code>@mentions...</code> — log an expense you paid, split equally (currency defaults to the group's if omitted)
/members — list who I recognize in this group
/currency <code>currency</code> — set this group's default currency
/balance — see who owes you and who you owe
/expenses — last 10 expenses
/delete <code>id</code> — remove an expense (payer or admin only)
/settle <code>@person</code> <code>amount</code> — record that you paid them
/settle_suggest — minimal set of payments to settle the group up
/settlements — last 10 recorded settlements
/history — each member's balance after the last 20 splits and settlements
/help — this message"""

class HelpCommand(private val telegramApi: TelegramApi) {
    suspend fun handle(context: CommandContext) {
        telegramApi.sendMessage(context.chatId, HELP_TEXT)
    }
}

class StartCommand(
    private val groupRepository: GroupRepository,
    private val telegramApi: TelegramApi,
) {
    suspend fun handle(context: CommandContext) {
        val group = groupRepository.find(context.groupId) ?: error("Group ${context.groupId} not found")
        telegramApi.sendMessage(
            context.chatId,
            "Hi! I'll help you split expenses in this group.\n\n" +
                "This group's default currency is ${group.defaultCurrency} — change it anytime with /currency.\n\n" +
                "Before you can @mention someone in /split, they need to send me /start too.\n\n" +
                HELP_TEXT,
        )
    }
}
