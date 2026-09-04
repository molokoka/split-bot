package split.telegram

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json

class TelegramDtosSpec :
    StringSpec({

        val json = Json { ignoreUnknownKeys = true }

        "deserializes a getUpdates response with a text message" {
            val body =
                """
                {
                  "ok": true,
                  "result": [
                    {
                      "update_id": 123456,
                      "message": {
                        "message_id": 42,
                        "from": {"id": 987654321, "first_name": "Alice", "username": "alice_w"},
                        "chat": {"id": -1001234567890, "type": "supergroup"},
                        "text": "/add 90 dinner @bob"
                      }
                    }
                  ]
                }
                """.trimIndent()

            val parsed = json.decodeFromString(GetUpdatesResponse.serializer(), body)

            parsed shouldBe
                GetUpdatesResponse(
                    ok = true,
                    result =
                        listOf(
                            TgUpdate(
                                updateId = 123456,
                                message =
                                    TgMessage(
                                        messageId = 42,
                                        from = TgUser(id = 987654321, firstName = "Alice", username = "alice_w"),
                                        chat = TgChat(id = -1001234567890, type = "supergroup"),
                                        text = "/add 90 dinner @bob",
                                    ),
                            ),
                        ),
                )
        }

        "deserializes a getChatAdministrators response" {
            val body = """{"ok": true, "result": [{"status": "administrator", "user": {"id": 111, "first_name": "Bob"}}]}"""

            val parsed = json.decodeFromString(GetChatAdministratorsResponse.serializer(), body)

            parsed shouldBe
                GetChatAdministratorsResponse(
                    ok = true,
                    result = listOf(TgChatMember(status = "administrator", user = TgUser(id = 111, firstName = "Bob"))),
                )
        }

        "a message with no sender or text deserializes with nulls" {
            val body = """{"update_id": 1, "message": {"message_id": 1, "chat": {"id": 5, "type": "private"}}}"""

            val parsed = json.decodeFromString(TgUpdate.serializer(), body)

            parsed shouldBe
                TgUpdate(
                    updateId = 1,
                    message = TgMessage(messageId = 1, from = null, chat = TgChat(id = 5, type = "private"), text = null),
                )
        }

        "deserializes an update with a callback_query" {
            val body =
                """
                {
                  "update_id": 5,
                  "callback_query": {
                    "id": "cbq1",
                    "from": {"id": 1, "first_name": "Alice", "username": "alice_w"},
                    "message": {
                      "message_id": 42,
                      "chat": {"id": -100, "type": "group"}
                    },
                    "data": "split:mode:exact"
                  }
                }
                """.trimIndent()

            val parsed = json.decodeFromString(TgUpdate.serializer(), body)

            parsed shouldBe
                TgUpdate(
                    updateId = 5,
                    callbackQuery =
                        TgCallbackQuery(
                            id = "cbq1",
                            from = TgUser(id = 1, firstName = "Alice", username = "alice_w"),
                            message = TgMessage(messageId = 42, chat = TgChat(id = -100, type = "group")),
                            data = "split:mode:exact",
                        ),
                )
        }

        "deserializes a message that's a reply to another message" {
            val body =
                """
                {
                  "message_id": 7,
                  "from": {"id": 1, "first_name": "Alice"},
                  "chat": {"id": -100, "type": "group"},
                  "text": "50",
                  "reply_to_message": {
                    "message_id": 3,
                    "chat": {"id": -100, "type": "group"}
                  }
                }
                """.trimIndent()

            val parsed = json.decodeFromString(TgMessage.serializer(), body)

            parsed shouldBe
                TgMessage(
                    messageId = 7,
                    from = TgUser(id = 1, firstName = "Alice"),
                    chat = TgChat(id = -100, type = "group"),
                    text = "50",
                    replyToMessage = TgMessage(messageId = 3, chat = TgChat(id = -100, type = "group")),
                )
        }
    })
