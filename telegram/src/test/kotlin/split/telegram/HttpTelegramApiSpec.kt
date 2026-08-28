package split.telegram

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import kotlinx.serialization.json.Json

class HttpTelegramApiSpec : StringSpec({

    fun clientReturning(body: String): Pair<HttpClient, MutableList<HttpRequestData>> {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            respond(
                content = ByteReadChannel(body),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        return client to requests
    }

    "getUpdates parses the response and requests the right path" {
        val (httpClient, requests) = clientReturning(
            """{"ok":true,"result":[{"update_id":1,"message":{"message_id":10,
                "from":{"id":99,"first_name":"Alice","username":"alice"},
                "chat":{"id":-100,"type":"group"},"text":"/help"}}]}""",
        )
        val api = HttpTelegramApi(botToken = "test-token", httpClient = httpClient)

        val updates = api.getUpdates(offset = null, timeoutSeconds = 0)

        updates shouldBe listOf(
            TgUpdate(
                updateId = 1,
                message = TgMessage(
                    messageId = 10,
                    from = TgUser(id = 99, firstName = "Alice", username = "alice"),
                    chat = TgChat(id = -100, type = "group"),
                    text = "/help",
                ),
            ),
        )
        requests.single().url.toString() shouldBe "https://api.telegram.org/bottest-token/getUpdates?timeout=0"
    }

    "getUpdates includes the offset when given" {
        val (httpClient, requests) = clientReturning("""{"ok":true,"result":[]}""")
        val api = HttpTelegramApi(botToken = "tok", httpClient = httpClient)

        api.getUpdates(offset = 42, timeoutSeconds = 5)

        requests.single().url.toString() shouldBe "https://api.telegram.org/bottok/getUpdates?timeout=5&offset=42"
    }

    "sendMessage posts chat_id and text as JSON" {
        val (httpClient, requests) = clientReturning("""{"ok":true}""")
        val api = HttpTelegramApi(botToken = "tok", httpClient = httpClient)

        api.sendMessage(chatId = -100, text = "hi")

        requests.single().body.toByteArray().decodeToString() shouldBe """{"chat_id":-100,"text":"hi"}"""
    }

    "getChatAdministrators parses the response" {
        val (httpClient, _) = clientReturning(
            """{"ok":true,"result":[{"status":"creator","user":{"id":7,"first_name":"Owner"}}]}""",
        )
        val api = HttpTelegramApi(botToken = "tok", httpClient = httpClient)

        val admins = api.getChatAdministrators(chatId = -100)

        admins shouldBe listOf(TgChatMember(status = "creator", user = TgUser(id = 7, firstName = "Owner")))
    }
})
