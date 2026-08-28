package split.telegram

import org.jetbrains.exposed.v1.jdbc.Database
import split.storage.connectDatabase
import java.nio.file.Files

/** Mirrors `storage`'s `withTestDatabase` — a real temp-file SQLite database migrated
 * through the actual Flyway path, since `storage`'s test source set isn't visible here. */
suspend fun withTestDatabase(test: suspend (Database) -> Unit) {
    val dbFile = Files.createTempFile("split-telegram-test-", ".db")
    Files.delete(dbFile)
    val path = dbFile.toString()

    try {
        test(connectDatabase(path))
    } finally {
        Files.deleteIfExists(dbFile)
        Files.deleteIfExists(dbFile.resolveSibling(dbFile.fileName.toString() + "-wal"))
        Files.deleteIfExists(dbFile.resolveSibling(dbFile.fileName.toString() + "-shm"))
    }
}
