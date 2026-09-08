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

data class DmCommandContext(
    val chatId: Long,
    val memberId: MemberId,
    val externalUserId: String,
    val args: String,
)

data class GroupJoinContext(
    val chatId: Long,
    val groupId: GroupId,
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
typealias DmCommandHandler = suspend (DmCommandContext) -> Unit
typealias GroupJoinHandler = suspend (GroupJoinContext) -> Unit
typealias CallbackHandler = suspend (CallbackContext) -> Unit
typealias ReplyHandler = suspend (ReplyContext) -> Unit

data class CallbackRouting(
    val flowHandler: CallbackHandler? = null,
    val staticHandlers: Map<String, CallbackHandler> = emptyMap(),
)

data class DmRouting(
    val handlers: Map<String, DmCommandHandler> = emptyMap(),
    val fallbackHandler: DmCommandHandler? = null,
)

data class ReplyRouting(
    val handler: ReplyHandler? = null,
    val isTracked: (chatId: Long, messageId: Long) -> Boolean = { _, _ -> false },
)

private val chatMemberAbsentStatuses = setOf("left", "kicked")
private val chatMemberPresentStatuses = setOf("member", "administrator")

class CommandRouter(
    private val identityResolver: IdentityResolver,
    private val handlers: Map<String, CommandHandler>,
    private val callbacks: CallbackRouting = CallbackRouting(),
    private val reply: ReplyRouting = ReplyRouting(),
    private val dm: DmRouting = DmRouting(),
    private val groupJoinHandler: GroupJoinHandler? = null,
) {
    suspend fun handleUpdate(update: TgUpdate) {
        val myChatMember = update.myChatMember
        if (myChatMember != null) {
            handleMyChatMember(myChatMember)
            return
        }

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

    private suspend fun handleMyChatMember(update: TgChatMemberUpdated) {
        val handler = groupJoinHandler ?: return
        val botWasJustAddedToAGroup =
            update.chat.type != "private" &&
                update.oldChatMember.status in chatMemberAbsentStatuses &&
                update.newChatMember.status in chatMemberPresentStatuses
        if (!botWasJustAddedToAGroup) return

        val groupId = identityResolver.resolveGroup(update.chat.id.toString())
        handler(GroupJoinContext(update.chat.id, groupId))
    }

    private suspend fun handleCallbackQuery(callbackQuery: TgCallbackQuery) {
        val message = callbackQuery.message ?: return
        val data = callbackQuery.data ?: return
        val handler = callbacks.staticHandlers[data] ?: callbacks.flowHandler ?: return

        val memberId =
            identityResolver.resolveMember(
                callbackQuery.from.id.toString(),
                callbackQuery.from.username,
                callbackQuery.from.firstName,
            )
        val groupId = identityResolver.resolveGroup(message.chat.id.toString())
        identityResolver.ensureGroupMembership(groupId, memberId)

        handler(CallbackContext(message.chat.id, memberId, groupId, callbackQuery.id, message.messageId, data))
    }

    private suspend fun handleCommand(
        message: TgMessage,
        from: TgUser,
        text: String,
    ) {
        if (message.chat.type == "private") {
            handlePrivateCommand(message, from, text)
            return
        }

        val (command, args) = parseCommand(text)
        val handler = handlers[command] ?: return

        val memberId = identityResolver.resolveMember(from.id.toString(), from.username, from.firstName)
        val groupId = identityResolver.resolveGroup(message.chat.id.toString())
        identityResolver.ensureGroupMembership(groupId, memberId)

        handler(CommandContext(message.chat.id, memberId, from.id.toString(), groupId, args))
    }

    private suspend fun handlePrivateCommand(
        message: TgMessage,
        from: TgUser,
        text: String,
    ) {
        val (command, args) = parseCommand(text)
        val handler = dm.handlers[command] ?: dm.fallbackHandler ?: return

        val memberId = identityResolver.resolveMember(from.id.toString(), from.username, from.firstName)

        handler(DmCommandContext(message.chat.id, memberId, from.id.toString(), args))
    }

    private suspend fun handleReply(
        message: TgMessage,
        from: TgUser,
        text: String,
    ) {
        val handler = reply.handler ?: return
        val replyToId = message.replyToMessage?.messageId ?: return
        if (!reply.isTracked(message.chat.id, replyToId)) return

        val memberId = identityResolver.resolveMember(from.id.toString(), from.username, from.firstName)
        val groupId = identityResolver.resolveGroup(message.chat.id.toString())
        identityResolver.ensureGroupMembership(groupId, memberId)

        handler(ReplyContext(message.chat.id, memberId, groupId, replyToId, text))
    }
}
