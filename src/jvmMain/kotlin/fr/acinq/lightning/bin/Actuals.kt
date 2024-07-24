package fr.acinq.lightning.bin

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.PublicKey
import fr.acinq.lightning.bin.conf.EnvVars.PHOENIX_DATADIR
import fr.acinq.phoenix.db.PhoenixDatabase
import okio.Path
import okio.Path.Companion.toPath
import java.util.*

actual val datadir: Path = (System.getenv()[PHOENIX_DATADIR]?.toPath() ?: System.getProperty("user.home").toPath().div(".phoenix"))

actual fun createAppDbDriver(dir: Path, chain: Chain, nodeId: PublicKey): SqlDriver {
    val path = dir / "phoenix.${chain.name.lowercase()}.${nodeId.toHex().take(6)}.db"
    // We have to use migrateEmptySchema = true because for the first release we called Schema.create(driver), which
    // didn't set the version to 1.
    val driver = JdbcSqliteDriver("jdbc:sqlite:$path", Properties(), PhoenixDatabase.Schema, migrateEmptySchema = true)
    return driver
}
