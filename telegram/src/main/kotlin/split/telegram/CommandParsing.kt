package split.telegram

import java.math.BigDecimal

internal val mentionPattern = Regex("@([a-zA-Z][a-zA-Z0-9_]{4,31})")

fun parseCommand(text: String): Pair<String, String> {
    val trimmed = text.trim()
    val spaceIndex = trimmed.indexOf(' ')
    val rawCommand = if (spaceIndex == -1) trimmed else trimmed.substring(0, spaceIndex)
    val args = if (spaceIndex == -1) "" else trimmed.substring(spaceIndex + 1).trim()
    val command = rawCommand.removePrefix("/").substringBefore('@').lowercase()
    return command to args
}

fun extractMentions(text: String): List<String> =
    mentionPattern.findAll(text).map { it.groupValues[1] }.toList()

data class AddExpenseArgs(
    val amount: BigDecimal,
    val currency: String,
    val description: String,
    val mentionUsernames: List<String>,
)

private val currencyCodePattern = Regex("^[A-Z]{3}$")

fun parseAddArgs(args: String, defaultCurrency: String): AddExpenseArgs {
    val mentions = extractMentions(args)
    require(mentions.isNotEmpty()) { "Mention at least one participant, e.g. /add 90 dinner <code>@alice</code> <code>@bob</code>" }

    val withoutMentions = mentionPattern.replace(args, "").trim().replace(Regex("\\s+"), " ")
    val parts = withoutMentions.split(" ", limit = 2)
    require(parts.size == 2) {
        "Usage: /add <code>amount</code> <code>currency</code> (optional) <code>description</code> <code>@mentions...</code>"
    }

    val amount = BigDecimal(parts[0])
    val rest = parts[1]
    val restParts = rest.split(" ", limit = 2)

    return if (restParts.size == 2 && currencyCodePattern.matches(restParts[0])) {
        AddExpenseArgs(amount, restParts[0], restParts[1], mentions)
    } else {
        AddExpenseArgs(amount, defaultCurrency, rest, mentions)
    }
}

data class SettleArgs(val counterpartyUsername: String, val amount: BigDecimal)

fun parseSettleArgs(args: String): SettleArgs {
    val mentions = extractMentions(args)
    require(mentions.size == 1) { "Usage: /settle <code>@person</code> <code>amount</code>" }
    val withoutMention = mentionPattern.replace(args, "").trim()
    require(withoutMention.isNotEmpty()) { "Usage: /settle <code>@person</code> <code>amount</code>" }
    return SettleArgs(mentions[0], BigDecimal(withoutMention))
}
