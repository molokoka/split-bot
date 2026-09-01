package split.telegram

// Sent with parse_mode HTML (see HttpTelegramApi), so placeholders use <code>...</code>
// rather than bare <angle brackets> — a raw "<amount>" would be read as an (invalid,
// unclosed) HTML tag and mangle or break the message.
internal const val HELP_TEXT = """Commands:
/add <code>amount</code> [CURRENCY] <code>description</code> @mentions... — log an expense you paid, split equally
/members — list who I recognize in this group
/currency <code>currency_code</code> — set this group's default currency
/balances — see who owes you and who you owe
/list — last 10 expenses
/delete <code>id</code> — remove an expense (payer or admin only)
/settle @person <code>amount</code> — record that you paid them
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
