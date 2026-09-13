package split.storage

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class SplitDatabaseSpec : StringSpec({

    "migrates a fresh database file and applies WAL/foreign-key/busy-timeout pragmas" {
        val dbFile = Files.createTempFile("split-test-", ".db")
        Files.delete(dbFile) // SQLite must create the file itself on first connection
        val path = dbFile.toString()

        try {
            connectDatabase(path)

            sqliteDataSource(path).connection.use { connection ->
                val tableNames = mutableListOf<String>()
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name != 'flyway_schema_history'",
                    ).use { rs ->
                        while (rs.next()) tableNames += rs.getString("name")
                    }
                }
                tableNames shouldContainExactlyInAnyOrder listOf(
                    "group_",
                    "member",
                    "group_member",
                    "expense",
                    "expense_share",
                    "settlement",
                    "platform_identity",
                    "platform_group_link",
                    "split_flow_state",
                )

                connection.createStatement().use { statement ->
                    statement.executeQuery("PRAGMA journal_mode").use { rs ->
                        rs.next()
                        rs.getString(1) shouldBe "wal"
                    }
                    statement.executeQuery("PRAGMA foreign_keys").use { rs ->
                        rs.next()
                        rs.getInt(1) shouldBe 1
                    }
                    statement.executeQuery("PRAGMA synchronous").use { rs ->
                        rs.next()
                        rs.getInt(1) shouldBe 1 // NORMAL
                    }
                    statement.executeQuery("PRAGMA busy_timeout").use { rs ->
                        rs.next()
                        rs.getInt(1) shouldBe 5000
                    }
                }
            }
        } finally {
            Files.deleteIfExists(dbFile)
            Files.deleteIfExists(dbFile.resolveSibling(dbFile.fileName.toString() + "-wal"))
            Files.deleteIfExists(dbFile.resolveSibling(dbFile.fileName.toString() + "-shm"))
        }
    }
})
