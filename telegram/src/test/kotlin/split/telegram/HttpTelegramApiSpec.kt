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
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; classDiscriminator = "type" }) }
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

    "sendMessage posts chat_id and text as JSON and returns the sent message id" {
        val (httpClient, requests) = clientReturning("""{"ok":true,"result":{"message_id":55,"chat":{"id":-100,"type":"group"}}}""")
        val api = HttpTelegramApi(botToken = "tok", httpClient = httpClient)

        val messageId = api.sendMessage(chatId = -100, text = "hi")

        requests.single().body.toByteArray().decodeToString() shouldBe """{"chat_id":-100,"text":"hi","parse_mode":"HTML"}"""
        messageId shouldBe 55L
    }

    "sendMessage includes reply_markup when a keyboard is given" {
        val (httpClient, requests) = clientReturning("""{"ok":true,"result":{"message_id":1,"chat":{"id":-100,"type":"group"}}}""")
        val api = HttpTelegramApi(botToken = "tok", httpClient = httpClient)

        api.sendMessage(
            chatId = -100,
            text = "How should this be split?",
            keyboard = InlineKeyboardMarkup(
                inlineKeyboard = listOf(listOf(InlineKeyboardButton(text = "Equal", callbackData = "split:mode:equal"))),
            ),
        )

        requests.single().body.toByteArray().decodeToString() shouldBe
            """{"chat_id":-100,"text":"How should this be split?","parse_mode":"HTML",""" +
            """"reply_markup":{"inline_keyboard":[[{"text":"Equal","callback_data":"split:mode:equal"}]]}}"""
    }

    "sendRichMessage posts chat_id and rich_message as JSON and returns the sent message id" {
        val (httpClient, requests) = clientReturning("""{"ok":true,"result":{"message_id":56,"chat":{"id":-100,"type":"group"}}}""")
        val api = HttpTelegramApi(botToken = "tok", httpClient = httpClient)

        val messageId = api.sendRichMessage(
            chatId = -100,
            richMessage = InputRichMessage(
                blocks = listOf(
                    RichBlockTable(
                        cells = listOf(listOf(RichBlockTableCell("ID", isHeader = true)), listOf(RichBlockTableCell("e1"))),
                        caption = "Last 10 expenses",
                    ),
                ),
            ),
        )

        requests.single().body.toByteArray().decodeToString() shouldBe
            """{"chat_id":-100,"rich_message":{"blocks":[{"type":"table","cells":[[{"text":"ID","is_header":true}],[{"text":"e1"}]],""" +
            """"caption":"Last 10 expenses"}]}}"""
        messageId shouldBe 56L
    }

    "editMessageText posts chat_id, message_id, text, and an optional keyboard" {
        val (httpClient, requests) = clientReturning("""{"ok":true}""")
        val api = HttpTelegramApi(botToken = "tok", httpClient = httpClient)

        api.editMessageText(
            chatId = -100,
            messageId = 42,
            text = "Split cancelled.",
            keyboard = InlineKeyboardMarkup(inlineKeyboard = listOf(listOf(InlineKeyboardButton(text = "Confirm", callbackData = "split:confirm")))),
        )

        requests.single().body.toByteArray().decodeToString() shouldBe
            """{"chat_id":-100,"message_id":42,"text":"Split cancelled.","parse_mode":"HTML",""" +
            """"reply_markup":{"inline_keyboard":[[{"text":"Confirm","callback_data":"split:confirm"}]]}}"""
    }

    "editRichMessage posts chat_id, message_id, and rich_message" {
        val (httpClient, requests) = clientReturning("""{"ok":true}""")
        val api = HttpTelegramApi(botToken = "tok", httpClient = httpClient)

        api.editRichMessage(
            chatId = -100,
            messageId = 42,
            richMessage = InputRichMessage(blocks = listOf(RichBlockParagraph("Split cancelled."))),
        )

        requests.single().body.toByteArray().decodeToString() shouldBe
            """{"chat_id":-100,"message_id":42,"rich_message":{"blocks":[{"type":"paragraph","text":"Split cancelled."}]}}"""
    }

    "answerCallbackQuery posts callback_query_id, text, and show_alert" {
        val (httpClient, requests) = clientReturning("""{"ok":true}""")
        val api = HttpTelegramApi(botToken = "tok", httpClient = httpClient)

        api.answerCallbackQuery(callbackQueryId = "cbq1", text = "Only the invoker can do that.", showAlert = true)

        requests.single().body.toByteArray().decodeToString() shouldBe
            """{"callback_query_id":"cbq1","text":"Only the invoker can do that.","show_alert":true}"""
    }

    "answerCallbackQuery omits text and show_alert when not given" {
        val (httpClient, requests) = clientReturning("""{"ok":true}""")
        val api = HttpTelegramApi(botToken = "tok", httpClient = httpClient)

        api.answerCallbackQuery(callbackQueryId = "cbq1")

        requests.single().body.toByteArray().decodeToString() shouldBe """{"callback_query_id":"cbq1"}"""
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
