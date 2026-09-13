package split.telegram.api

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

class HttpTelegramApi(
    private val botToken: String,
    private val baseUrl: String = "https://api.telegram.org",
    private val httpClient: HttpClient =
        HttpClient(CIO) {
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        classDiscriminator = "type"
                    },
                )
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
            }
        },
) : TelegramApi {
    override suspend fun getUpdates(
        offset: Long?,
        timeoutSeconds: Int,
    ): List<TgUpdate> {
        val query =
            buildString {
                append("timeout=$timeoutSeconds")
                if (offset != null) append("&offset=$offset")
            }
        // Telegram holds this connection open for up to timeoutSeconds waiting for updates,
        // so the client-side timeout must comfortably exceed it or every long poll aborts.
        val response: GetUpdatesResponse =
            httpClient
                .get("$baseUrl/bot$botToken/getUpdates?$query") {
                    timeout { requestTimeoutMillis = (timeoutSeconds * 1000L) + 10_000 }
                }.body()
        return response.result
    }

    override suspend fun sendMessage(
        chatId: Long,
        text: String,
        keyboard: InlineKeyboardMarkup?,
    ): Long {
        val response: MessageResponse =
            httpClient
                .post("$baseUrl/bot$botToken/sendMessage") {
                    contentType(ContentType.Application.Json)
                    setBody(SendMessageRequest(chatId, text, parseMode = "HTML", replyMarkup = keyboard))
                }.body()
        return response.result.messageId
    }

    override suspend fun sendRichMessage(
        chatId: Long,
        richMessage: InputRichMessage,
        keyboard: InlineKeyboardMarkup?,
    ): Long {
        val response: MessageResponse =
            httpClient
                .post("$baseUrl/bot$botToken/sendRichMessage") {
                    contentType(ContentType.Application.Json)
                    setBody(SendRichMessageRequest(chatId, richMessage, replyMarkup = keyboard))
                }.body()
        return response.result.messageId
    }

    override suspend fun sendForceReplyPrompt(
        chatId: Long,
        text: String,
    ): Long {
        val response: MessageResponse =
            httpClient
                .post("$baseUrl/bot$botToken/sendMessage") {
                    contentType(ContentType.Application.Json)
                    setBody(SendForceReplyRequest(chatId, text, parseMode = "HTML", replyMarkup = ForceReply(forceReply = true)))
                }.body()
        return response.result.messageId
    }

    override suspend fun editMessageText(
        chatId: Long,
        messageId: Long,
        text: String,
        keyboard: InlineKeyboardMarkup?,
    ) {
        httpClient.post("$baseUrl/bot$botToken/editMessageText") {
            contentType(ContentType.Application.Json)
            setBody(EditMessageTextRequest(chatId, messageId, text, parseMode = "HTML", replyMarkup = keyboard))
        }
    }

    override suspend fun editRichMessage(
        chatId: Long,
        messageId: Long,
        richMessage: InputRichMessage,
        keyboard: InlineKeyboardMarkup?,
    ) {
        httpClient.post("$baseUrl/bot$botToken/editMessageText") {
            contentType(ContentType.Application.Json)
            setBody(EditRichMessageRequest(chatId, messageId, richMessage, replyMarkup = keyboard))
        }
    }

    override suspend fun deleteMessage(
        chatId: Long,
        messageId: Long,
    ) {
        httpClient.post("$baseUrl/bot$botToken/deleteMessage") {
            contentType(ContentType.Application.Json)
            setBody(DeleteMessageRequest(chatId, messageId))
        }
    }

    override suspend fun answerCallbackQuery(
        callbackQueryId: String,
        text: String?,
        showAlert: Boolean,
    ) {
        httpClient.post("$baseUrl/bot$botToken/answerCallbackQuery") {
            contentType(ContentType.Application.Json)
            setBody(AnswerCallbackQueryRequest(callbackQueryId, text, showAlert.takeIf { it }))
        }
    }

    override suspend fun getChatAdministrators(chatId: Long): List<TgChatMember> {
        val response: GetChatAdministratorsResponse =
            httpClient.get("$baseUrl/bot$botToken/getChatAdministrators?chat_id=$chatId").body()
        return response.result
    }
}
