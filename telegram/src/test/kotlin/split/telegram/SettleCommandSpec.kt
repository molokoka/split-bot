package split.telegram

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import split.storage.ExposedGroupRepository
import split.storage.ExposedMemberRepository
import split.storage.ExposedPlatformDirectory
import split.storage.ExposedSettlementRepository

class SettleCommandSpec : StringSpec({

    "records a settlement between the sender and the mentioned member" {
        withTestDatabase { db ->
            val platformDirectory = ExposedPlatformDirectory(db)
            val groupRepository = ExposedGroupRepository(db)
            val settlementRepository = ExposedSettlementRepository(db)
            val resolver = IdentityResolver(platformDirectory, ExposedMemberRepository(db), groupRepository)
            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            resolver.resolveMember("2", "bobby", "Bob")
            val groupId = resolver.resolveGroup("-100001")

            val telegramApi = FakeTelegramApi()
            val command = SettleCommand(platformDirectory, groupRepository, settlementRepository, telegramApi)

            command.handle(CommandContext(-100, aliceId, "1", groupId, "@bobby 20"))

            val settlement = settlementRepository.listActive(groupId, "USD").single()
            settlement.fromMemberId shouldBe aliceId
            settlement.amount shouldBe BigDecimal("20.00")
            telegramApi.sentMessages.single().first shouldBe -100L
        }
    }

    "records the settlement under the group's currency, not a hardcoded one" {
        withTestDatabase { db ->
            val platformDirectory = ExposedPlatformDirectory(db)
            val groupRepository = ExposedGroupRepository(db)
            val settlementRepository = ExposedSettlementRepository(db)
            val resolver = IdentityResolver(platformDirectory, ExposedMemberRepository(db), groupRepository)
            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            resolver.resolveMember("2", "bobby", "Bob")
            val groupId = resolver.resolveGroup("-100001")
            groupRepository.updateCurrency(groupId, "EUR")

            val telegramApi = FakeTelegramApi()
            val command = SettleCommand(platformDirectory, groupRepository, settlementRepository, telegramApi)

            command.handle(CommandContext(-100, aliceId, "1", groupId, "@bobby 20"))

            // if this were hardcoded to USD, listActive(groupId, "EUR") would come back empty
            // even though a settlement was created — exactly the bug this test guards against.
            val settlement = settlementRepository.listActive(groupId, "EUR").single()
            settlement.currency shouldBe "EUR"
            telegramApi.sentMessages.single().second shouldBe "Settlement recorded:\n\nYou paid 20.00 EUR."
        }
    }

    "replies when the mentioned person isn't recognized" {
        withTestDatabase { db ->
            val platformDirectory = ExposedPlatformDirectory(db)
            val groupRepository = ExposedGroupRepository(db)
            val settlementRepository = ExposedSettlementRepository(db)
            val resolver = IdentityResolver(platformDirectory, ExposedMemberRepository(db), groupRepository)
            val aliceId = resolver.resolveMember("1", "alice", "Alice")
            val groupId = resolver.resolveGroup("-100001")

            val telegramApi = FakeTelegramApi()
            val command = SettleCommand(platformDirectory, groupRepository, settlementRepository, telegramApi)

            command.handle(CommandContext(-100, aliceId, "1", groupId, "@stranger 20"))

            settlementRepository.listActive(groupId, "USD") shouldBe emptyList()
            telegramApi.sentMessages.single().second shouldBe
                "I don't recognize @stranger yet — ask them to run /start with me first."
        }
    }
})
