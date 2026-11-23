package ee.taltech.diploma

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.sql.SQLException

class HistoryOrderingTest {
    @ParameterizedTest
    @ValueSource(strings = ["approach_a", "approach_b"])
    fun `old parent change cannot invalidate a child already moved into history`(approach: String) = withDatabase(approach) { db ->
        db.connect().use { old ->
            old.contract()
            old.autoCommit = false
            old.scalar("SELECT transaction_timestamp()")
            db.connect().use { newer ->
                newer.price("[2026-08-01,2026-09-01)")
                newer.execute("DELETE FROM contract_price")
            }
            assertEquals("40001", assertThrows(SQLException::class.java) {
                old.execute("UPDATE contract SET valid_period = '[2026-01-01,2026-07-01)'")
            }.sqlState)
            old.rollback()
            assertEquals("[2026-01-01,2027-01-01)", old.scalar("SELECT valid_period::text FROM contract"))
        }
    }
}
