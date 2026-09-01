package split.telegram

import split.core.ExpenseRepository
import split.core.GroupRepository
import split.core.MemberRepository
import split.core.SettlementRepository
import split.core.computeBalances
import split.core.simplifyDebts

class BalancesCommand(
    private val groupRepository: GroupRepository,
    private val memberRepository: MemberRepository,
    private val expenseRepository: ExpenseRepository,
    private val settlementRepository: SettlementRepository,
    private val telegramApi: TelegramApi,
) {
    suspend fun handle(context: CommandContext) {
        val group = groupRepository.find(context.groupId) ?: error("Group ${context.groupId} not found")
        val expenses = expenseRepository.listActive(context.groupId, group.defaultCurrency)
        val settlements = settlementRepository.listActive(context.groupId, group.defaultCurrency)
        val balances = computeBalances(group.defaultCurrency, expenses, settlements)
        val payments = simplifyDebts(balances)
        val members = memberRepository.findByGroup(context.groupId)

        telegramApi.sendMessage(
            context.chatId,
            formatBalances(payments, members, context.memberId, group.defaultCurrency),
        )
    }
}
