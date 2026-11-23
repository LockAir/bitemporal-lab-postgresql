package ee.taltech.diploma

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.sql.SQLException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ConcurrencyTest {
    @ParameterizedTest
    @CsvSource("approach_a,true", "approach_a,false", "approach_b,true", "approach_b,false")
    fun `parent shortening races with child insertion in either order`(approach: String, parentFirst: Boolean) =
        withDatabase(approach) { db ->
            db.connect().use { setup -> setup.contract() }
            db.connect().use { first ->
                db.connect().use { second ->
                    first.autoCommit = false
                    second.autoCommit = false
                    val secondPid = second.scalar("SELECT pg_backend_pid()")
                    val shorten = "UPDATE contract SET valid_period = '[2026-01-01,2026-07-01)'"
                    val insert = "INSERT INTO contract_price(contract_id, price, valid_period) VALUES (1,100,'[2026-08-01,2026-09-01)')"
                    first.execute(if (parentFirst) shorten else insert)
                    val executor = Executors.newSingleThreadExecutor()
                    try {
                        val competing = executor.submit<String> {
                            try {
                                second.execute(if (parentFirst) insert else shorten)
                                second.commit()
                                "committed"
                            } catch (failure: SQLException) {
                                second.rollback()
                                failure.sqlState
                            }
                        }
                        // Observe a real lock wait, rather than relying on a fixed sleep.
                        db.connect().use { observer ->
                            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                            var waiting = false
                            while (!waiting && !competing.isDone && System.nanoTime() < deadline) {
                                waiting = observer.scalar("""
                                    SELECT EXISTS(SELECT FROM pg_stat_activity
                                    WHERE pid = $secondPid AND wait_event_type = 'Lock')
                                """.trimIndent()) == "t"
                                if (!waiting) Thread.sleep(10)
                            }
                            assertTrue(waiting, "Competing transaction should wait for a lock")
                        }
                        first.commit()
                        val outcome = competing.get(10, TimeUnit.SECONDS)
                        assertEquals("23503", outcome, "The losing operation must fail the coverage check")
                        db.connect().use { check ->
                            assertEquals("0", check.scalar("""
                                SELECT count(*) FROM contract_price p
                                WHERE NOT COALESCE((SELECT range_agg(c.valid_period) @> p.valid_period
                                    FROM contract c WHERE c.contract_id = p.contract_id), false)
                            """.trimIndent()))
                        }
                    } finally {
                        first.rollback()
                        executor.shutdownNow()
                        executor.awaitTermination(35, TimeUnit.SECONDS)
                        second.rollback()
                    }
                }
            }
        }
}
