package split.storage

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.jdbc.Database
import split.core.Expense
import split.core.ExpenseId
import split.core.ExpenseShare
import split.core.Group
import split.core.GroupId
import split.core.Member
import split.core.MemberId
import split.core.SplitType
import java.math.BigDecimal
import java.time.Instant

class ExposedExpenseRepositorySpec :
    StringSpec({

        val group = GroupId("g1")
        val alice = MemberId("alice")
        val bob = MemberId("bob")
        val carol = MemberId("carol")
        val createdAt = Instant.parse("2026-08-27T00:00:00Z")

        suspend fun seedGroupAndMembers(db: Database) {
            ExposedGroupRepository(db).create(Group(group, "USD", createdAt))
            ExposedMemberRepository(db).create(Member(alice, "Alice"))
            ExposedMemberRepository(db).create(Member(bob, "Bob"))
            ExposedMemberRepository(db).create(Member(carol, "Carol"))
        }

        fun dinner() =
            Expense(
                id = ExpenseId("e1"),
                groupId = group,
                currency = "USD",
                description = "dinner",
                amount = BigDecimal("90.00"),
                payerId = alice,
                splitType = SplitType.EQUAL,
                createdBy = alice,
                createdAt = createdAt,
                shares =
                    listOf(
                        ExpenseShare(alice, BigDecimal("30.00")),
                        ExpenseShare(bob, BigDecimal("30.00")),
                        ExpenseShare(carol, BigDecimal("30.00")),
                    ),
            )

        "creates an expense with its shares in one transaction and finds it back" {
            withTestDatabase { db ->
                seedGroupAndMembers(db)
                val repo = ExposedExpenseRepository(db)
                val expense = dinner()

                repo.create(expense)

                repo.find(expense.id) shouldBe expense
            }
        }

        "returns null for an expense that doesn't exist" {
            withTestDatabase { db ->
                ExposedExpenseRepository(db).find(ExpenseId("missing")) shouldBe null
            }
        }

        "listActive returns expenses for the group, excluding soft-deleted ones" {
            withTestDatabase { db ->
                seedGroupAndMembers(db)
                val repo = ExposedExpenseRepository(db)
                val active = dinner()
                val deleted =
                    active.copy(
                        id = ExpenseId("e2"),
                        description = "coffee",
                        deletedAt = Instant.parse("2026-08-27T01:00:00Z"),
                    )
                repo.create(active)
                repo.create(deleted)

                repo.listActive(group) shouldBe listOf(active)
            }
        }

        "listActive returns expenses regardless of currency" {
            withTestDatabase { db ->
                seedGroupAndMembers(db)
                val repo = ExposedExpenseRepository(db)
                val usd = dinner()
                val eur = usd.copy(id = ExpenseId("e2"), description = "hotel", currency = "EUR")
                repo.create(usd)
                repo.create(eur)

                repo.listActive(group).toSet() shouldBe setOf(usd, eur)
            }
        }

        "softDelete sets deletedAt so the expense no longer appears in listActive" {
            withTestDatabase { db ->
                seedGroupAndMembers(db)
                val repo = ExposedExpenseRepository(db)
                val expense = dinner()
                repo.create(expense)

                repo.softDelete(expense.id, Instant.parse("2026-08-27T01:00:00Z"))

                repo.listActive(group) shouldBe emptyList()
                repo.find(expense.id)?.deletedAt shouldBe Instant.parse("2026-08-27T01:00:00Z")
            }
        }
    })
