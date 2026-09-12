package split.telegram

import split.core.GroupId
import split.core.MemberId
import split.telegram.api.TgCallbackQuery
import split.telegram.api.TgMessage
import split.telegram.api.TgUpdate
import split.telegram.api.TgUser

data class CommandContext(
    val chatId: Long,
    val memberId: MemberId,
    val externalUserId: String,
    val groupId: GroupId,
    val args: String,
)

data class CallbackContext(
    val chatId: Long,
    val memberId: MemberId,
    val groupId: GroupId,
    val callbackQueryId: String,
    val messageId: Long,
    val data: String,
)

data class ReplyContext(
    val chatId: Long,
    val memberId: MemberId,
    val groupId: GroupId,
    val replyToMessageId: Long,
    val text: String,
)

typealias CommandHandler = suspend (CommandContext) -> Unit
typealias CallbackHandler = suspend (CallbackContext) -> Unit
typealias ReplyHandler = suspend (ReplyContext) -> Unit

class CommandRouter(
    private val identityResolver: IdentityResolver,
    private val handlers: Map<String, CommandHandler>,
    private val callbackHandler: CallbackHandler? = null,
    private val replyHandler: ReplyHandler? = null,
    private val isTrackedReply: suspend (chatId: Long, messageId: Long) -> Boolean = { _, _ -> false },
) {
    suspend fun handleUpdate(update: TgUpdate) {
        val callbackQuery = update.callbackQuery
        if (callbackQuery != null) {
            handleCallbackQuery(callbackQuery)
            return
        }

        val message = update.message ?: return
        val from = message.from ?: return
        val text = message.text ?: return

        if (text.startsWith("/")) {
            handleCommand(message, from, text)
            return
        }

        handleReply(message, from, text)
    }

    private suspend fun handleCallbackQuery(callbackQuery: TgCallbackQuery) {
        val handler = callbackHandler ?: return
        val message = callbackQuery.message ?: return
        val data = callbackQuery.data ?: return

        val memberId =
            identityResolver.resolveMember(
                callbackQuery.from.id.toString(),
                callbackQuery.from.username,
                callbackQuery.from.firstName,
            )
        val groupId = identityResolver.resolveGroup(message.chat.id.toString())
        identityResolver.ensureGroupMembership(groupId, memberId)

        println("callback \"$data\" chat=${message.chat.id} member=$memberId")
        handler(CallbackContext(message.chat.id, memberId, groupId, callbackQuery.id, message.messageId, data))
    }

    private suspend fun handleCommand(
        message: TgMessage,
        from: TgUser,
        text: String,
    ) {
        val (command, args) = parseCommand(text)
        val handler = handlers[command]
        if (handler == null) {
            println("unknown command \"/$command\" chat=${message.chat.id}")
            return
        }

        val memberId = identityResolver.resolveMember(from.id.toString(), from.username, from.firstName)
        val groupId = identityResolver.resolveGroup(message.chat.id.toString())
        identityResolver.ensureGroupMembership(groupId, memberId)

        println("command \"/$command\" chat=${message.chat.id} member=$memberId args=\"$args\"")
        handler(CommandContext(message.chat.id, memberId, from.id.toString(), groupId, args))
    }

    private suspend fun handleReply(
        message: TgMessage,
        from: TgUser,
        text: String,
    ) {
        val handler = replyHandler ?: return
        val replyToId = message.replyToMessage?.messageId
        if (replyToId == null) {
            println("ignoring non-reply text chat=${message.chat.id}")
            return
        }
        if (!isTrackedReply(message.chat.id, replyToId)) {
            println("ignoring reply to untracked message $replyToId chat=${message.chat.id}")
            return
        }

        val memberId = identityResolver.resolveMember(from.id.toString(), from.username, from.firstName)
        val groupId = identityResolver.resolveGroup(message.chat.id.toString())
        identityResolver.ensureGroupMembership(groupId, memberId)

        println("reply to $replyToId chat=${message.chat.id} member=$memberId")
        handler(ReplyContext(message.chat.id, memberId, groupId, replyToId, text))
    }
}
