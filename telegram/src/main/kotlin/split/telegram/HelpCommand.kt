package split.telegram

internal const val HELP_TEXT = """Commands:
/add <amount> [CURRENCY] <description> @mentions... — log an expense you paid, split equally
/members — list who I recognize in this group
/members add <name> — add someone without Telegram to split with
/currency <code> — set this group's default currency
/balances — see who owes you and who you owe
/list — last 10 expenses
/delete <id> — remove an expense (payer or admin only)
/settle @person <amount> — record that you paid them
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
