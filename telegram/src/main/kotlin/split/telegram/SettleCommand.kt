package split.telegram

import split.core.GroupRepository
import split.core.PlatformDirectory
import split.core.SettlementId
import split.core.SettlementRepository
import split.core.createSettlement
import java.time.Clock
import java.time.Instant
import java.util.UUID

class SettleCommand(
    private val platformDirectory: PlatformDirectory,
    private val groupRepository: GroupRepository,
    private val settlementRepository: SettlementRepository,
    private val telegramApi: TelegramApi,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun handle(context: CommandContext) {
        val parsed = try {
            parseSettleArgs(context.args)
        } catch (e: IllegalArgumentException) {
            telegramApi.sendMessage(context.chatId, e.message ?: "Invalid /settle usage")
            return
        }

        val counterpartyId = platformDirectory.findMemberByUsername(IdentityResolver.PLATFORM, parsed.counterpartyUsername)
        if (counterpartyId == null) {
            telegramApi.sendMessage(
                context.chatId,
                "I don't recognize <code>@${parsed.counterpartyUsername}</code> yet — ask them to run /start with me first.",
            )
            return
        }

        val group = groupRepository.find(context.groupId) ?: error("Group ${context.groupId} not found")

        val settlement = try {
            createSettlement(
                id = SettlementId(idGenerator()),
                groupId = context.groupId,
                currency = group.defaultCurrency,
                from = context.memberId,
                to = counterpartyId,
                amount = parsed.amount,
                createdBy = context.memberId,
                createdAt = Instant.now(clock),
            )
        } catch (e: IllegalArgumentException) {
            telegramApi.sendMessage(context.chatId, e.message ?: "Invalid /settle amount")
            return
        }

        settlementRepository.create(settlement)
        telegramApi.sendMessage(
            context.chatId,
            "Settlement recorded:\n\nYou paid ${formatAmount(settlement.amount, settlement.currency)}.",
        )
    }
}
