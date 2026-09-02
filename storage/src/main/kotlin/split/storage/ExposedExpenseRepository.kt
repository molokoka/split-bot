package split.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import split.core.Expense
import split.core.ExpenseId
import split.core.ExpenseRepository
import split.core.ExpenseShare
import split.core.GroupId
import split.core.MemberId
import split.core.SplitType

class ExposedExpenseRepository(private val db: Database) : ExpenseRepository {

    override suspend fun create(expense: Expense): Unit = withContext(Dispatchers.IO) {
        suspendTransaction(db) {
            ExpenseTable.insert {
                it[id] = expense.id.value
                it[groupId] = expense.groupId.value
                it[currency] = expense.currency
                it[description] = expense.description
                it[amountCents] = expense.amount.toCents()
                it[payerId] = expense.payerId.value
                it[splitType] = expense.splitType.name
                it[createdBy] = expense.createdBy.value
                it[createdAt] = expense.createdAt.toString()
                it[deletedAt] = expense.deletedAt?.toString()
            }
            ExpenseShareTable.batchInsert(expense.shares) { share ->
                this[ExpenseShareTable.expenseId] = expense.id.value
                this[ExpenseShareTable.memberId] = share.memberId.value
                this[ExpenseShareTable.shareAmountCents] = share.shareAmount.toCents()
            }
        }
    }

    override suspend fun find(id: ExpenseId): Expense? = withContext(Dispatchers.IO) {
        suspendTransaction(db) {
            val row = ExpenseTable.selectAll()
                .where { ExpenseTable.id eq id.value }
                .singleOrNull() ?: return@suspendTransaction null

            row.toExpense(sharesFor(id))
        }
    }

    override suspend fun listActive(groupId: GroupId): List<Expense> =
        withContext(Dispatchers.IO) {
            suspendTransaction(db) {
                ExpenseTable.selectAll()
                    .where {
                        (ExpenseTable.groupId eq groupId.value) and
                            ExpenseTable.deletedAt.isNull()
                    }
                    .map { row -> row.toExpense(sharesFor(ExpenseId(row[ExpenseTable.id]))) }
            }
        }

    override suspend fun softDelete(id: ExpenseId, deletedAt: Instant): Unit = withContext(Dispatchers.IO) {
        suspendTransaction(db) {
            ExpenseTable.update({ ExpenseTable.id eq id.value }) {
                it[this.deletedAt] = deletedAt.toString()
            }
        }
    }

    private fun sharesFor(id: ExpenseId): List<ExpenseShare> =
        ExpenseShareTable.selectAll()
            .where { ExpenseShareTable.expenseId eq id.value }
            .orderBy(ExpenseShareTable.memberId)
            .map {
                ExpenseShare(
                    memberId = MemberId(it[ExpenseShareTable.memberId]),
                    shareAmount = it[ExpenseShareTable.shareAmountCents].centsToAmount(),
                )
            }
}

private fun ResultRow.toExpense(shares: List<ExpenseShare>) = Expense(
    id = ExpenseId(this[ExpenseTable.id]),
    groupId = GroupId(this[ExpenseTable.groupId]),
    currency = this[ExpenseTable.currency],
    description = this[ExpenseTable.description],
    amount = this[ExpenseTable.amountCents].centsToAmount(),
    payerId = MemberId(this[ExpenseTable.payerId]),
    splitType = SplitType.valueOf(this[ExpenseTable.splitType]),
    createdBy = MemberId(this[ExpenseTable.createdBy]),
    createdAt = Instant.parse(this[ExpenseTable.createdAt]),
    shares = shares,
    deletedAt = this[ExpenseTable.deletedAt]?.let { Instant.parse(it) },
)
