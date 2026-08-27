package split.core

import java.math.BigDecimal

fun createSettlement(
    id: SettlementId,
    groupId: GroupId,
    currency: String,
    from: MemberId,
    to: MemberId,
    amount: BigDecimal,
): Settlement {
    require(amount > BigDecimal.ZERO) { "Settlement amount must be positive, was $amount" }
    require(from != to) { "Cannot settle with yourself" }

    return Settlement(
        id = id,
        groupId = groupId,
        currency = currency,
        fromMemberId = from,
        toMemberId = to,
        amount = amount,
    )
}

fun canDeleteExpense(
    expense: Expense,
    requesterId: MemberId,
    requesterIsGroupAdmin: Boolean,
): Boolean = requesterId == expense.payerId || requesterIsGroupAdmin
