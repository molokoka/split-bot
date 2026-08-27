package split.core

import java.math.BigDecimal
import java.math.RoundingMode

fun resolveEqualSplit(
    amount: BigDecimal,
    payerId: MemberId,
    participantIds: List<MemberId>,
): List<ExpenseShare> {
    val perShare = amount
        .divide(BigDecimal(participantIds.size), 2, RoundingMode.DOWN)

    val shares = participantIds.associateWith { perShare }.toMutableMap()

    val leftover = amount - perShare.multiply(BigDecimal(participantIds.size))
    shares[payerId] = shares.getValue(payerId) + leftover

    return participantIds.map { ExpenseShare(it, shares.getValue(it)) }
}

fun resolveExactSplit(
    amount: BigDecimal,
    amounts: Map<MemberId, BigDecimal>,
): List<ExpenseShare> {
    val total = amounts.values.fold(BigDecimal.ZERO) { acc, v -> acc + v }
    require(total.compareTo(amount) == 0) {
        "Exact split amounts sum to $total but expense total is $amount"
    }

    return amounts.map { (memberId, shareAmount) -> ExpenseShare(memberId, shareAmount) }
}

fun resolveSharesSplit(
    amount: BigDecimal,
    payerId: MemberId,
    shareCounts: Map<MemberId, Int>,
): List<ExpenseShare> {
    val totalShares = shareCounts.values.sum()
    val perUnit = amount.divide(BigDecimal(totalShares), 2, RoundingMode.DOWN)

    val shares = shareCounts.mapValues { (_, count) -> perUnit.multiply(BigDecimal(count)) }.toMutableMap()

    val leftover = amount - shares.values.fold(BigDecimal.ZERO) { acc, v -> acc + v }
    shares[payerId] = shares.getValue(payerId) + leftover

    return shareCounts.keys.map { ExpenseShare(it, shares.getValue(it)) }
}
