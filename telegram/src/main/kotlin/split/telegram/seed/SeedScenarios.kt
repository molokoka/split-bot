package split.telegram.seed

import split.core.Expense
import split.core.ExpenseId
import split.core.ExpenseRepository
import split.core.ExpenseShare
import split.core.GroupId
import split.core.MemberId
import split.core.SplitType
import split.core.computeBalances
import split.core.resolveEqualSplit
import split.core.resolveExactSplit
import split.core.resolveSharesSplit
import split.telegram.IdentityResolver
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * A named starting state for manual testing, applied to whichever database `SPLIT_DB_PATH` points
 * at. Scenarios build their state through [IdentityResolver] and the ordinary repositories, so what
 * they leave behind is what the bot itself would have written.
 *
 * Deliberately limited to reference data — members, expenses, settlements. In-flight split flows are
 * not seedable: a `split_flow_state` row is keyed by Telegram message ids, so a fabricated one would
 * point at messages that were never sent, leaving a row with no table to look at and no buttons to
 * tap. Seed the members, then start the split by hand.
 */
internal class ScenarioContext(
    val resolver: IdentityResolver,
    val expenseRepository: ExpenseRepository,
    val groupId: GroupId,
) {
    private val memberIds = mutableMapOf<String, MemberId>()

    /** Adds [username] to the group if they aren't in it already, and remembers them for [id]. */
    suspend fun member(username: String): MemberId {
        val displayName = username.replaceFirstChar { it.uppercase() }
        val memberId = resolver.resolveMember("test-$username", username, displayName)
        resolver.ensureGroupMembership(groupId, memberId)
        memberIds[username] = memberId
        return memberId
    }

    fun id(username: String): MemberId = memberIds.getValue(username)

    suspend fun equalExpense(
        description: String,
        amount: String,
        paidBy: String,
        daysAgo: Long,
        between: List<String>,
    ) {
        val total = BigDecimal(amount)
        record(
            description,
            total,
            paidBy,
            daysAgo,
            Split(SplitType.EQUAL, resolveEqualSplit(total, id(paidBy), between.map(::id))),
        )
    }

    suspend fun exactExpense(
        description: String,
        amount: String,
        paidBy: String,
        daysAgo: Long,
        amounts: Map<String, String>,
    ) {
        val total = BigDecimal(amount)
        val byMember = amounts.entries.associate { (username, share) -> id(username) to BigDecimal(share) }
        record(description, total, paidBy, daysAgo, Split(SplitType.EXACT, resolveExactSplit(total, byMember)))
    }

    suspend fun sharesExpense(
        description: String,
        amount: String,
        paidBy: String,
        daysAgo: Long,
        shares: Map<String, Int>,
    ) {
        val total = BigDecimal(amount)
        val byMember = shares.entries.associate { (username, count) -> id(username) to count }
        record(
            description,
            total,
            paidBy,
            daysAgo,
            Split(SplitType.SHARES, resolveSharesSplit(total, id(paidBy), byMember)),
        )
    }

    private data class Split(
        val type: SplitType,
        val shares: List<ExpenseShare>,
    )

    private suspend fun record(
        description: String,
        amount: BigDecimal,
        paidBy: String,
        daysAgo: Long,
        split: Split,
    ) {
        expenseRepository.create(
            Expense(
                id = ExpenseId(UUID.randomUUID().toString()),
                groupId = groupId,
                currency = IdentityResolver.DEFAULT_CURRENCY,
                description = description,
                amount = amount,
                payerId = id(paidBy),
                splitType = split.type,
                createdBy = id(paidBy),
                // Spread history out, so /history and /expenses aren't all stamped the same instant.
                createdAt = Instant.now().minus(daysAgo, ChronoUnit.DAYS),
                shares = split.shares,
            ),
        )
    }
}

internal class Scenario(
    val name: String,
    val description: String,
    val apply: suspend ScenarioContext.() -> Unit,
)

private val members =
    Scenario("members", "Three members and nothing else — enough to drive /split by hand") {
        listOf("alice", "bobby", "carol").forEach { member(it) }
    }

private val expenses =
    Scenario("expenses", "members, plus one expense of each split type across a few days") {
        members.apply(this)
        exactExpense(
            "dinner",
            "90.00",
            paidBy = "alice",
            daysAgo = 3,
            amounts = mapOf("alice" to "50.00", "bobby" to "40.00"),
        )
        equalExpense("lunch", "30.00", paidBy = "bobby", daysAgo = 2, between = listOf("alice", "bobby", "carol"))
        sharesExpense(
            "taxi",
            "24.00",
            paidBy = "carol",
            daysAgo = 1,
            shares = mapOf("alice" to 2, "bobby" to 1, "carol" to 1),
        )
    }

private val unbalanced =
    Scenario("unbalanced", "Debts arranged in a chain, so /settle_suggest has something to simplify") {
        members.apply(this)
        // Each person covers the next, so nobody nets out and the naive settlement is a cycle.
        exactExpense("groceries", "60.00", paidBy = "alice", daysAgo = 4, amounts = mapOf("bobby" to "60.00"))
        exactExpense("cinema", "45.00", paidBy = "bobby", daysAgo = 3, amounts = mapOf("carol" to "45.00"))
        exactExpense("brunch", "33.00", paidBy = "carol", daysAgo = 2, amounts = mapOf("alice" to "33.00"))
    }

private val bigGroup =
    Scenario("big-group", "Eight members, to see how the tables and keyboards hold up when lists get long") {
        listOf("alice", "bobby", "carol", "dave", "erin", "frank", "grace", "heidi").forEach { member(it) }
    }

internal val SCENARIOS = listOf(members, expenses, unbalanced, bigGroup)

/** Net balance per member after a scenario, so the tool can report what it actually produced. */
internal suspend fun ScenarioContext.balancesAfterSeeding(): Map<MemberId, BigDecimal> =
    computeBalances(
        IdentityResolver.DEFAULT_CURRENCY,
        expenseRepository.listActive(groupId),
        settlements = emptyList(),
    )
