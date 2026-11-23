package ee.taltech.diploma

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.postgresql.PGConnection
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException

class CopyLoadTest {
    @TempDir
    lateinit var temporary: Path

    @ParameterizedTest
    @ValueSource(strings = ["approach_a", "approach_b"])
    fun `generated CSV loads with constraints and history enabled`(approach: String) = withDatabase(approach) { db ->
        val directory = temporary.resolve("data")
        generateData(1000, 42, directory)
        db.connect().use { c ->
            c.autoCommit = false
            val copy = c.unwrap(PGConnection::class.java).copyAPI
            Files.newBufferedReader(directory.resolve("batch_00000_contracts.csv")).use {
                assertEquals(1000L, copy.copyIn("COPY contract(contract_id,valid_period) FROM STDIN WITH (FORMAT csv, HEADER true)", it))
            }
            Files.newBufferedReader(directory.resolve("batch_00000_prices.csv")).use {
                assertEquals(5000L, copy.copyIn("COPY contract_price(contract_id,price,valid_period) FROM STDIN WITH (FORMAT csv, HEADER true)", it))
            }
            c.commit()
            c.autoCommit = true
            Files.lines(directory.resolve("corrections.csv")).use { lines ->
                lines.skip(1).forEach { line ->
                    val values = line.split(',')
                    c.prepareStatement("SELECT correct_price(?, daterange(?::date, ?::date, '[)'), ?)").use {
                        it.setLong(1, values[0].toLong())
                        it.setString(2, values[1])
                        it.setString(3, values[2])
                        it.setBigDecimal(4, values[3].toBigDecimal())
                        it.execute()
                    }
                }
            }
            assertEquals("5200", c.scalar("SELECT count(*) FROM contract_price"))
            assertEquals("100", c.scalar("SELECT count(*) FROM contract_price_history"))
            assertEquals("0", c.scalar("SELECT count(*) FROM contract_price_history WHERE isempty(system_period)"))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["approach_a", "approach_b"])
    fun `unsupported isolation and truncate are explicitly rejected`(approach: String) = withDatabase(approach) { db ->
        db.connect().use { c ->
            c.contract()
            assertEquals("0A000", assertThrows(SQLException::class.java) {
                c.execute("TRUNCATE contract_price")
            }.sqlState)
            c.autoCommit = false
            c.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ")
            assertEquals("0A000", assertThrows(SQLException::class.java) { c.price() }.sqlState)
            c.rollback()
        }
    }
}
