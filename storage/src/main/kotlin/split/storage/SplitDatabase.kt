package split.storage

import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.sqlite.SQLiteDataSource

/**
 * A [SQLiteDataSource] for [path] with the WAL/foreign-key/busy-timeout pragmas
 * baked in, applied to every connection the driver hands out. `internal` so tests
 * can open raw connections through the same configuration [connectDatabase] uses.
 */
internal fun sqliteDataSource(path: String): SQLiteDataSource =
    SQLiteDataSource().apply {
        url = "jdbc:sqlite:$path"
        setJournalMode("WAL")
        setSynchronous("NORMAL")
        setEnforceForeignKeys(true)
        setBusyTimeout(5000)
    }

/**
 * Opens the SQLite database at [path], runs pending Flyway migrations, and
 * returns the connected Exposed [Database].
 */
fun connectDatabase(path: String): Database {
    val dataSource = sqliteDataSource(path)

    Flyway
        .configure()
        .dataSource(dataSource)
        .load()
        .migrate()

    return Database.connect(dataSource)
}

/** Reads [connectDatabase]'s path from `SPLIT_DB_PATH`, defaulting to a local dev file. */
fun connectDatabaseFromEnv(): Database = connectDatabase(System.getenv("SPLIT_DB_PATH") ?: "./split-dev.db")
