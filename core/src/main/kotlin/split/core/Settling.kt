package split.core

import java.math.BigDecimal
import java.time.Instant

fun createSettlement(
    id: SettlementId,
    groupId: GroupId,
    currency: String,
    from: MemberId,
    to: MemberId,
    amount: BigDecimal,
    createdBy: MemberId,
    createdAt: Instant,
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
        createdBy = createdBy,
        createdAt = createdAt,
    )
}

fun canDeleteExpense(
    expense: Expense,
    requesterId: MemberId,
    requesterIsGroupAdmin: Boolean,
): Boolean = requesterId == expense.payerId || requesterIsGroupAdmin

fun canDeleteSettlement(
    settlement: Settlement,
    requesterId: MemberId,
    requesterIsGroupAdmin: Boolean,
): Boolean = requesterId == settlement.createdBy || requesterIsGroupAdmin
