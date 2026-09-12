package split.telegram.commands

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import split.core.MemberId
import split.storage.ExposedGroupRepository
import split.storage.ExposedMemberRepository
import split.storage.ExposedPlatformDirectory
import split.telegram.CommandContext
import split.telegram.FakeTelegramApi
import split.telegram.IdentityResolver
import split.telegram.split
import split.telegram.withTestDatabase

class CurrencyCommandSpec :
    StringSpec({

        "sets the group's default currency" {
            withTestDatabase { db ->
                val groupRepository = ExposedGroupRepository(db)
                val resolver = IdentityResolver(ExposedPlatformDirectory(db), ExposedMemberRepository(db), groupRepository)
                val groupId = resolver.resolveGroup("-100001")
                val telegramApi = FakeTelegramApi()
                val command = CurrencyCommand(groupRepository, telegramApi)

                command.handle(CommandContext(-100, MemberId("m1"), "1", groupId, "EUR"))

                groupRepository.find(groupId)!!.defaultCurrency shouldBe "EUR"
                telegramApi.sentMessages shouldBe
                    listOf(
                        -100L to (
                            "Currency updated:\n\n" +
                                "This group's default currency is now EUR — new expenses use EUR unless you set a " +
                                "different currency inline, e.g. <code>/split 90 USD dinner @bob</code>."
                        ),
                    )
            }
        }

        "rejects an invalid currency code" {
            withTestDatabase { db ->
                val groupRepository = ExposedGroupRepository(db)
                val resolver = IdentityResolver(ExposedPlatformDirectory(db), ExposedMemberRepository(db), groupRepository)
                val groupId = resolver.resolveGroup("-100001")
                val telegramApi = FakeTelegramApi()
                val command = CurrencyCommand(groupRepository, telegramApi)

                command.handle(CommandContext(-100, MemberId("m1"), "1", groupId, "not a code"))

                groupRepository.find(groupId)!!.defaultCurrency shouldBe IdentityResolver.DEFAULT_CURRENCY
                telegramApi.sentMessages shouldBe listOf(-100L to "Usage: /currency <code>currency</code>, e.g. /currency EUR")
            }
        }
    })
