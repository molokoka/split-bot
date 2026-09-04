package split.telegram

import split.core.SplitType
import java.math.BigDecimal

internal val mentionPattern = Regex("@([a-zA-Z][a-zA-Z0-9_]{4,31})")
private val numberAfterMention = Regex("^\\s+(\\d+(?:\\.\\d+)?)")

fun parseCommand(text: String): Pair<String, String> {
    val trimmed = text.trim()
    val spaceIndex = trimmed.indexOf(' ')
    val rawCommand = if (spaceIndex == -1) trimmed else trimmed.substring(0, spaceIndex)
    val args = if (spaceIndex == -1) "" else trimmed.substring(spaceIndex + 1).trim()
    val command = rawCommand.removePrefix("/").substringBefore('@').lowercase()
    return command to args
}

fun extractMentions(text: String): List<String> = mentionPattern.findAll(text).map { it.groupValues[1] }.toList()

data class PartialSplitArgs(
    val splitTypeHint: SplitType?,
    val amount: BigDecimal?,
    val currency: String,
    val description: String?,
    val mentionUsernames: List<String>,
    val exactAmounts: List<BigDecimal>?,
)

private val currencyCodePattern = Regex("^[A-Z]{3}$")

fun parseSplitArgs(
    args: String,
    defaultCurrency: String,
): PartialSplitArgs {
    val trimmedArgs = args.trim()
    val firstToken = trimmedArgs.substringBefore(' ', trimmedArgs)
    val splitTypeHint =
        when (firstToken.lowercase()) {
            "equal" -> SplitType.EQUAL
            "exact" -> SplitType.EXACT
            else -> null
        }
    val remaining = if (splitTypeHint != null) trimmedArgs.substringAfter(' ', "").trim() else trimmedArgs

    val mentionUsernames = mutableListOf<String>()
    val exactAmountStrings = mutableListOf<String?>()
    val withoutMentionsBuilder = StringBuilder()
    var cursor = 0
    for (match in mentionPattern.findAll(remaining)) {
        withoutMentionsBuilder.append(remaining, cursor, match.range.first)
        mentionUsernames += match.groupValues[1]
        var endIndex = match.range.last + 1
        val numberMatch = numberAfterMention.find(remaining.substring(endIndex))
        if (numberMatch != null) {
            exactAmountStrings += numberMatch.groupValues[1]
            endIndex += numberMatch.range.last + 1
        } else {
            exactAmountStrings += null
        }
        cursor = endIndex
    }
    withoutMentionsBuilder.append(remaining, cursor, remaining.length)
    val withoutMentions = withoutMentionsBuilder.toString().trim().replace(Regex("\\s+"), " ")

    val exactAmounts =
        when {
            exactAmountStrings.isEmpty() || exactAmountStrings.all { it == null } -> null
            exactAmountStrings.all { it != null } -> exactAmountStrings.map { BigDecimal(it!!) }
            else -> throw IllegalArgumentException(
                "Give every mentioned person an amount, or none — not a mix, e.g. <code>@alice 50 @bob 40</code>",
            )
        }
    exactAmounts?.forEach { amount ->
        require(amount.signum() > 0 && amount.scale() <= 2) {
            "Amounts must be positive with at most 2 decimal places, e.g. <code>50</code> or <code>42.50</code>."
        }
    }
    if (splitTypeHint == SplitType.EQUAL && exactAmounts != null) {
        throw IllegalArgumentException("You said <code>equal</code> but gave specific amounts — did you mean <code>exact</code>?")
    }

    if (withoutMentions.isEmpty()) {
        return PartialSplitArgs(splitTypeHint, null, defaultCurrency, null, mentionUsernames, exactAmounts)
    }

    val firstWord = withoutMentions.substringBefore(' ', withoutMentions)
    val amount = firstWord.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }

    if (amount == null) {
        return PartialSplitArgs(splitTypeHint, null, defaultCurrency, withoutMentions, mentionUsernames, exactAmounts)
    }

    val afterAmount = withoutMentions.substringAfter(' ', "").trim()
    val afterAmountParts = afterAmount.split(" ", limit = 2)
    return if (afterAmountParts.size == 2 && currencyCodePattern.matches(afterAmountParts[0])) {
        PartialSplitArgs(splitTypeHint, amount, afterAmountParts[0], afterAmountParts[1].ifBlank { null }, mentionUsernames, exactAmounts)
    } else {
        PartialSplitArgs(splitTypeHint, amount, defaultCurrency, afterAmount.ifBlank { null }, mentionUsernames, exactAmounts)
    }
}

data class SettleArgs(
    val counterpartyUsername: String,
    val amount: BigDecimal,
)

fun parseSettleArgs(args: String): SettleArgs {
    val mentions = extractMentions(args)
    require(mentions.size == 1) { "Usage: /settle <code>@person</code> <code>amount</code>" }
    val withoutMention = mentionPattern.replace(args, "").trim()
    require(withoutMention.isNotEmpty()) { "Usage: /settle <code>@person</code> <code>amount</code>" }
    return SettleArgs(mentions[0], BigDecimal(withoutMention))
}
