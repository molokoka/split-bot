package split.telegram

import split.core.GroupId
import split.core.MemberId

data class CommandContext(
    val chatId: Long,
    val memberId: MemberId,
    val externalUserId: String,
    val groupId: GroupId,
    val args: String,
)

typealias CommandHandler = suspend (CommandContext) -> Unit

class CommandRouter(
    private val identityResolver: IdentityResolver,
    private val handlers: Map<String, CommandHandler>,
) {
    suspend fun handleUpdate(update: TgUpdate) {
        val message = update.message ?: return
        val from = message.from ?: return
        val text = message.text ?: return

        val memberId = identityResolver.resolveMember(from.id.toString(), from.username, from.firstName)
        val groupId = identityResolver.resolveGroup(message.chat.id.toString())
        identityResolver.ensureGroupMembership(groupId, memberId)

        if (!text.startsWith("/")) return

        val (command, args) = parseCommand(text)
        val handler = handlers[command] ?: return
        handler(CommandContext(message.chat.id, memberId, from.id.toString(), groupId, args))
    }
}
