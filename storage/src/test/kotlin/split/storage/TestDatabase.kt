package split.storage

import org.jetbrains.exposed.v1.jdbc.Database
import java.nio.file.Files

/**
 * Runs [test] against a real temp-file SQLite database migrated through the actual
 * Flyway path (not `:memory:` - see the spec's Testing section for why: each JDBC
 * connection to `:memory:` is its own separate database unless a shared-cache URI
 * is forced).
 */
suspend fun withTestDatabase(test: suspend (Database) -> Unit) {
    val dbFile = Files.createTempFile("split-test-", ".db")
    Files.delete(dbFile) // SQLite must create the file itself on first connection
    val path = dbFile.toString()

    try {
        test(connectDatabase(path))
    } finally {
        Files.deleteIfExists(dbFile)
        Files.deleteIfExists(dbFile.resolveSibling(dbFile.fileName.toString() + "-wal"))
        Files.deleteIfExists(dbFile.resolveSibling(dbFile.fileName.toString() + "-shm"))
    }
}
