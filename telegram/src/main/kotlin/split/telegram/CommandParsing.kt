package split.telegram

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
