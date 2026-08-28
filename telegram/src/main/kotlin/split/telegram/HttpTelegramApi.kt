package split.telegram

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

class HttpTelegramApi(
    private val botToken: String,
    private val baseUrl: String = "https://api.telegram.org",
    private val httpClient: HttpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    },
) : TelegramApi {

    override suspend fun getUpdates(offset: Long?, timeoutSeconds: Int): List<TgUpdate> {
        val query = buildString {
            append("timeout=$timeoutSeconds")
            if (offset != null) append("&offset=$offset")
        }
        val response: GetUpdatesResponse = httpClient.get("$baseUrl/bot$botToken/getUpdates?$query").body()
        return response.result
    }

    override suspend fun sendMessage(chatId: Long, text: String) {
        httpClient.post("$baseUrl/bot$botToken/sendMessage") {
            contentType(ContentType.Application.Json)
            setBody(SendMessageRequest(chatId, text))
        }
    }

    override suspend fun getChatAdministrators(chatId: Long): List<TgChatMember> {
        val response: GetChatAdministratorsResponse =
            httpClient.get("$baseUrl/bot$botToken/getChatAdministrators?chat_id=$chatId").body()
        return response.result
    }
}
