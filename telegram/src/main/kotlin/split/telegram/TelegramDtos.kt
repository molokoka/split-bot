package split.telegram

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TgUser(
    val id: Long,
    @SerialName("first_name") val firstName: String,
    val username: String? = null,
)

@Serializable
data class TgChat(
    val id: Long,
    val type: String,
)

@Serializable
data class TgMessage(
    @SerialName("message_id") val messageId: Long,
    val from: TgUser? = null,
    val chat: TgChat,
    val text: String? = null,
)

@Serializable
data class TgUpdate(
    @SerialName("update_id") val updateId: Long,
    val message: TgMessage? = null,
)

@Serializable
data class TgChatMember(
    val status: String,
    val user: TgUser,
)

@Serializable
data class GetUpdatesResponse(
    val ok: Boolean,
    val result: List<TgUpdate> = emptyList(),
)

@Serializable
data class GetChatAdministratorsResponse(
    val ok: Boolean,
    val result: List<TgChatMember> = emptyList(),
)

@Serializable
data class SendMessageRequest(
    @SerialName("chat_id") val chatId: Long,
    val text: String,
)
