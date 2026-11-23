package ee.taltech.diploma

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.sql.SQLException

class TemporalDatabaseTest {
    @ParameterizedTest
    @ValueSource(strings = ["approach_a", "approach_b"])
    fun `adjacent periods are allowed and overlap is rejected`(approach: String) = withDatabase(approach) { db ->
        db.connect().use { c ->
            c.contract("[2026-01-01,2026-07-01)")
            c.contract("[2026-07-01,2027-01-01)")
            assertEquals("23P01", assertThrows(SQLException::class.java) {
                c.contract("[2026-06-01,2026-08-01)")
            }.sqlState)
            c.price()
            c.price("[2026-02-01,2026-03-01)")
            assertEquals("23P01", assertThrows(SQLException::class.java) {
                c.price("[2026-01-15,2026-02-15)")
            }.sqlState)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["approach_a", "approach_b"])
    fun `null empty invalid price and missing parent are rejected`(approach: String) = withDatabase(approach) { db ->
        db.connect().use { c ->
            assertEquals("23514", assertThrows(SQLException::class.java) { c.contract("empty") }.sqlState)
            assertEquals("23502", assertThrows(SQLException::class.java) {
                c.execute("INSERT INTO contract(contract_id, valid_period) VALUES (1, NULL)")
            }.sqlState)
            assertEquals("23503", assertThrows(SQLException::class.java) { c.price() }.sqlState)
            c.contract()
            assertEquals("23514", assertThrows(SQLException::class.java) { c.price(amount = -1) }.sqlState)
            assertEquals("0", c.scalar("SELECT count(*) FROM contract_price"))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["approach_a", "approach_b"])
    fun `several parent periods cover a child but a one day gap does not`(approach: String) = withDatabase(approach) { db ->
        db.connect().use { c ->
            c.contract("[2026-01-01,2026-07-01)")
            c.contract("[2026-07-01,2027-01-01)")
            c.price("[2026-06-01,2026-08-01)")
            c.contract("[2026-01-01,2026-07-01)", 2)
            c.contract("[2026-07-02,2027-01-01)", 2)
            assertEquals("23503", assertThrows(SQLException::class.java) {
                c.price("[2026-06-01,2026-08-01)", id = 2)
            }.sqlState)
            assertEquals("23503", assertThrows(SQLException::class.java) {
                c.price("[2026-12-01,2027-01-02)")
            }.sqlState)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["approach_a", "approach_b"])
    fun `parent shortening and deletion cannot orphan prices`(approach: String) = withDatabase(approach) { db ->
        db.connect().use { c ->
            c.contract()
            c.price("[2026-08-01,2026-09-01)")
            for (sql in listOf(
                "UPDATE contract SET valid_period = '[2026-01-01,2026-07-01)'",
                "DELETE FROM contract"
            )) {
                assertEquals("23503", assertThrows(SQLException::class.java) { c.execute(sql) }.sqlState)
            }
            assertEquals("0", c.scalar("SELECT count(*) FROM contract_history"))
            assertEquals("1", c.scalar("SELECT count(*) FROM contract"))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["approach_a", "approach_b"])
    fun `child period update is checked`(approach: String) = withDatabase(approach) { db ->
        db.connect().use { c ->
            c.contract()
            c.price()
            assertEquals("23503", assertThrows(SQLException::class.java) {
                c.execute("UPDATE contract_price SET valid_period = '[2026-01-01,2027-01-02)'")
            }.sqlState)
            assertEquals("0", c.scalar("SELECT count(*) FROM contract_price_history"))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["approach_a", "approach_b"])
    fun `open ended validity is supported`(approach: String) = withDatabase(approach) { db ->
        db.connect().use { c ->
            c.contract("[2026-01-01,)")
            c.price("[2026-02-01,)")
            assertEquals("1", c.scalar("SELECT count(*) FROM contract_price WHERE valid_period @> DATE '2100-01-01'"))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["approach_a", "approach_b"])
    fun `repeated updates keep one previous version per transaction`(approach: String) = withDatabase(approach) { db ->
        db.connect().use { c ->
            c.contract()
            c.price()
            c.autoCommit = false
            c.execute("UPDATE contract_price SET price = 110")
            c.execute("UPDATE contract_price SET price = 120")
            c.commit()
            c.autoCommit = true
            assertEquals("120.00", c.scalar("SELECT price FROM contract_price"))
            assertEquals("100.00", c.scalar("SELECT price FROM contract_price_history"))
            assertEquals("1", c.scalar("SELECT count(*) FROM contract_price_history"))
            assertEquals("0", c.scalar("SELECT count(*) FROM contract_price_history WHERE isempty(system_period)"))
            assertEquals("t", c.scalar("SELECT upper(h.system_period) = lower(c.system_period) FROM contract_price_history h JOIN contract_price c USING (row_id)"))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["approach_a", "approach_b"])
    fun `insert update delete in one transaction leaves no version`(approach: String) = withDatabase(approach) { db ->
        db.connect().use { c ->
            c.contract()
            c.autoCommit = false
            c.price()
            c.execute("UPDATE contract_price SET price = 120")
            c.execute("DELETE FROM contract_price")
            c.commit()
            assertEquals("0", c.scalar("SELECT count(*) FROM contract_price_versions"))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["approach_a", "approach_b"])
    fun `rollback restores current data and history`(approach: String) = withDatabase(approach) { db ->
        db.connect().use { c ->
            c.contract()
            c.price()
            c.autoCommit = false
            c.execute("UPDATE contract_price SET price = 120")
            c.execute("DELETE FROM contract_price")
            c.rollback()
            assertEquals("100.00", c.scalar("SELECT price FROM contract_price"))
            assertEquals("0", c.scalar("SELECT count(*) FROM contract_price_history"))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["approach_a", "approach_b"])
    fun `deleting a committed price closes its history`(approach: String) = withDatabase(approach) { db ->
        db.connect().use { c ->
            c.contract()
            c.price()
            c.execute("DELETE FROM contract_price")
            assertEquals("0", c.scalar("SELECT count(*) FROM contract_price"))
            assertEquals("1", c.scalar("SELECT count(*) FROM contract_price_history WHERE NOT upper_inf(system_period)"))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["approach_a", "approach_b"])
    fun `retroactive correction splits a period and preserves both time dimensions`(approach: String) = withDatabase(approach) { db ->
        db.connect().use { c ->
            c.contract()
            c.price()
            val before = c.scalar("SELECT lower(system_period)::text FROM contract_price")
            c.execute("SELECT correct_price(1, '[2026-01-10,2026-01-20)', 120)")
            val after = c.scalar("SELECT lower(system_period)::text FROM contract_price WHERE price = 120")
            assertEquals("3", c.scalar("SELECT count(*) FROM contract_price"))
            fun amount(day: String, time: String) = c.prepareStatement("""
                SELECT p.price FROM contract_price_versions p
                JOIN contract_versions c ON c.contract_id = p.contract_id
                WHERE p.contract_id = 1 AND p.valid_period @> ?::date
                  AND c.valid_period @> ?::date
                  AND p.system_period @> ?::timestamptz
                  AND c.system_period @> ?::timestamptz
            """.trimIndent()).use {
                it.setString(1, day); it.setString(2, day)
                it.setString(3, time); it.setString(4, time)
                it.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    val result = rows.getString(1)
                    assertFalse(rows.next())
                    result
                }
            }
            assertEquals("100.00", amount("2026-01-15", before))
            assertEquals("120.00", amount("2026-01-15", after))
            assertEquals("100.00", amount("2026-01-09", after))
            assertEquals("100.00", amount("2026-01-20", after))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["approach_a", "approach_b"])
    fun `correction across two prices preserves outside fragments`(approach: String) = withDatabase(approach) { db ->
        db.connect().use { c ->
            c.contract()
            c.price()
            c.price("[2026-02-01,2026-03-01)", 200)
            c.execute("SELECT correct_price(1, '[2026-01-20,2026-02-10)', 150)")
            assertEquals("3", c.scalar("SELECT count(*) FROM contract_price"))
            assertEquals("2", c.scalar("SELECT count(*) FROM contract_price_history"))
            assertEquals("200.00", c.scalar("SELECT price FROM contract_price WHERE valid_period @> DATE '2026-02-10'"))
            assertEquals("150.00", c.scalar("SELECT price FROM contract_price WHERE valid_period @> DATE '2026-02-01'"))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["approach_a", "approach_b"])
    fun `correction across a price gap fails atomically`(approach: String) = withDatabase(approach) { db ->
        db.connect().use { c ->
            c.contract()
            c.price()
            c.price("[2026-02-02,2026-03-01)", 200)
            assertEquals("23514", assertThrows(SQLException::class.java) {
                c.execute("SELECT correct_price(1, '[2026-01-20,2026-02-10)', 150)")
            }.sqlState)
            assertEquals("2", c.scalar("SELECT count(*) FROM contract_price"))
            assertEquals("0", c.scalar("SELECT count(*) FROM contract_price_history"))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["approach_a", "approach_b"])
    fun `old transaction cannot overwrite a version created after its start`(approach: String) = withDatabase(approach) { db ->
        db.connect().use { old ->
            old.contract()
            old.price()
            old.autoCommit = false
            old.scalar("SELECT transaction_timestamp()")
            db.connect().use { newer -> newer.execute("UPDATE contract_price SET price = 110") }
            assertEquals("40001", assertThrows(SQLException::class.java) {
                old.execute("UPDATE contract_price SET price = 120")
            }.sqlState)
            old.rollback()
            assertEquals("110.00", old.scalar("SELECT price FROM contract_price"))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["approach_a", "approach_b"])
    fun `parent versions preserve history and identifiers are immutable`(approach: String) = withDatabase(approach) { db ->
        db.connect().use { c ->
            c.contract()
            c.execute("UPDATE contract SET valid_period = '[2026-01-01,2028-01-01)'")
            assertEquals("1", c.scalar("SELECT count(*) FROM contract_history"))
            assertEquals("23514", assertThrows(SQLException::class.java) {
                c.execute("UPDATE contract SET contract_id = 2")
            }.sqlState)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["approach_a", "approach_b"])
    fun `old transaction cannot backdate a child before its parent`(approach: String) = withDatabase(approach) { db ->
        db.connect().use { old ->
            old.autoCommit = false
            old.scalar("SELECT transaction_timestamp()")
            db.connect().use { newer -> newer.contract() }
            assertEquals("40001", assertThrows(SQLException::class.java) { old.price() }.sqlState)
            old.rollback()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["approach_a", "approach_b"])
    fun `old transaction cannot reuse a period occupied by intervening history`(approach: String) = withDatabase(approach) { db ->
        db.connect().use { old ->
            old.contract()
            old.autoCommit = false
            old.scalar("SELECT transaction_timestamp()")
            db.connect().use { newer ->
                newer.price()
                newer.execute("DELETE FROM contract_price")
            }
            assertEquals("40001", assertThrows(SQLException::class.java) { old.price() }.sqlState)
            old.rollback()
        }
    }
}
