package split.core

import java.math.BigDecimal
import java.time.Instant

@JvmInline
value class MemberId(val value: String)

@JvmInline
value class GroupId(val value: String)

@JvmInline
value class ExpenseId(val value: String)

@JvmInline
value class SettlementId(val value: String)

data class Member(
    val id: MemberId,
    val displayName: String,
)

data class Group(
    val id: GroupId,
    val defaultCurrency: String,
)

enum class SplitType { EQUAL, EXACT, SHARES }

data class ExpenseShare(
    val memberId: MemberId,
    val shareAmount: BigDecimal,
)

data class Expense(
    val id: ExpenseId,
    val groupId: GroupId,
    val currency: String,
    val description: String,
    val amount: BigDecimal,
    val payerId: MemberId,
    val splitType: SplitType,
    val shares: List<ExpenseShare>,
    val deletedAt: Instant? = null,
)

data class Settlement(
    val id: SettlementId,
    val groupId: GroupId,
    val currency: String,
    val fromMemberId: MemberId,
    val toMemberId: MemberId,
    val amount: BigDecimal,
)
