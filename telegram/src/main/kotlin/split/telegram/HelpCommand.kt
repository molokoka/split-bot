package split.telegram

// Sent with parse_mode HTML (see HttpTelegramApi), so placeholders use <code>...</code>
// rather than bare <angle brackets> — a raw "<amount>" would be read as an (invalid,
// unclosed) HTML tag and mangle or break the message. The @-prefixed placeholders
// (@mentions, @person) are wrapped in <code> too, for a different reason: Telegram
// can't nest other entities inside code/pre, so this is also what stops it from reading
// literal example text like "@person" as a real mention and pinging whichever account
// happens to hold that username.
internal const val HELP_TEXT = """Commands:

/add <code>amount</code> [<code>currency</code>] <code>description</code> <code>@mentions...</code> — log an expense you paid, split equally
/members — list who I recognize in this group
/currency <code>currency</code> — set this group's default currency
/balances — see who owes you and who you owe
/list — last 10 expenses
/delete <code>id</code> — remove an expense (payer or admin only)
/settle <code>@person</code> <code>amount</code> — record that you paid them
/settle_suggest — minimal set of payments to settle the group up
/help — this message"""

class HelpCommand(private val telegramApi: TelegramApi) {
    suspend fun handle(context: CommandContext) {
        telegramApi.sendMessage(context.chatId, HELP_TEXT)
    }
}

class StartCommand(private val telegramApi: TelegramApi) {
    suspend fun handle(context: CommandContext) {
        telegramApi.sendMessage(context.chatId, "Hi! I'll help you split expenses in this group.\n\n$HELP_TEXT")
    }
}
