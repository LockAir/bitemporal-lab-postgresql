package ee.taltech.diploma

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Controlled interleavings, not a throughput or latency benchmark. */
object ConcurrencyRunner {
    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }
    private fun Connection.scalar(sql: String): String = createStatement().use { s ->
        s.executeQuery(sql).use { r -> check(r.next()); r.getString(1) }
    }
    @JvmStatic fun main(args: Array<String>) {
        require(args.size <= 1) { "Argument: repetitions" }
        val repetitions = args.firstOrNull()?.toInt() ?: 20
        require(repetitions in 1..1000)
        val run = UUID.randomUUID().toString().replace("-", "")
        val output = Path.of("results", "concurrency_$run")
        Files.createDirectories(output)
        val metadata = StringBuilder("Controlled parent/child conflict; READ COMMITTED; repetitions=$repetitions\n")
        metadata.appendLine("JVM=${System.getProperty("java.version")} OS=${System.getProperty("os.name")}")
        metadata.appendLine("First operation completes without commit; second starts; observer verifies blocker PID; first commits or rolls back.")
        metadata.appendLine("Wait events describe locking, not performance. All final rows and history are checked. No retries.")
        val counts = linkedMapOf<String, Int>()
        Files.newBufferedWriter(output.resolve("raw.csv")).use { raw ->
            raw.appendLine("repetition,approach,first_operation,first_end,wait_event_type,wait_event,blocked_by_first,second_outcome,expected_outcome,current_valid,history_valid,status")
            repeat(repetitions) { index ->
                val approaches = if (index % 2 == 0) listOf("approach_a", "approach_b") else listOf("approach_b", "approach_a")
                for (approach in approaches) for (parentFirst in listOf(true, false)) for (commitFirst in listOf(true, false)) {
                    val schema = "race_" + UUID.randomUUID().toString().replace("-", "")
                    Database.connect(approach).use { admin ->
                        admin.exec("CREATE SCHEMA $schema")
                        try {
                            Database.connect(approach, schema).use { setup ->
                                setup.autoCommit = false
                                for (file in listOf("common.sql.inc", "$approach.sql.inc")) {
                                    setup.exec(Files.readString(Path.of("sql", file)))
                                }
                                setup.commit()
                                setup.exec("INSERT INTO contract(contract_id,valid_period) VALUES(1,'[2026-01-01,2027-01-01)')")
                                setup.commit()
                                if (index == 0 && parentFirst && commitFirst) {
                                    metadata.appendLine("$approach: ${setup.scalar("SELECT version()")}")
                                    metadata.appendLine("isolation=${setup.scalar("SHOW transaction_isolation")}")
                                    Files.writeString(output.resolve("environment.txt"), metadata)
                                }
                            }
                            Database.connect(approach, schema).use { first ->
                                Database.connect(approach, schema).use { second ->
                                    val firstPid = first.scalar("SELECT pg_backend_pid()").toInt()
                                    val secondPid = second.scalar("SELECT pg_backend_pid()").toInt()
                                    first.autoCommit = false
                                    second.autoCommit = false
                                    val shorten = "UPDATE contract SET valid_period='[2026-01-01,2026-07-01)' WHERE contract_id=1"
                                    val insert = "INSERT INTO contract_price(contract_id,price,valid_period) VALUES(1,100,'[2026-08-01,2026-09-01)')"
                                    val executor = Executors.newSingleThreadExecutor()
                                    try {
                                        first.exec(if (parentFirst) shorten else insert)
                                        val competing = executor.submit<String> {
                                            try {
                                                second.exec(if (parentFirst) insert else shorten)
                                                second.commit()
                                                "committed"
                                            } catch (e: SQLException) {
                                                second.rollback()
                                                e.sqlState ?: "unknown"
                                            }
                                        }
                                        var event: String? = null
                                        Database.connect(approach, schema).use { observer ->
                                            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                                            while (event == null && !competing.isDone && System.nanoTime() < deadline) {
                                                observer.createStatement().use { s ->
                                                    s.executeQuery("SELECT wait_event FROM pg_stat_activity WHERE pid=$secondPid AND wait_event_type='Lock' AND $firstPid=ANY(pg_blocking_pids(pid))").use { r ->
                                                        if (r.next()) event = r.getString(1)
                                                    }
                                                }
                                                if (event == null) Thread.sleep(10)
                                            }
                                        }
                                        if (event == null) {
                                            raw.appendLine("${index+1},$approach,${if(parentFirst) "parent" else "child"},${if(commitFirst) "commit" else "rollback"},,,false,unknown,unknown,false,false,missing_lock")
                                            raw.flush()
                                            error("Expected second transaction to wait for first: $approach")
                                        }
                                        if (commitFirst) first.commit() else first.rollback()
                                        val outcome = competing.get(15, TimeUnit.SECONDS)
                                        val expected = if (commitFirst) "23503" else "committed"
                                        val parentChanged = parentFirst == commitFirst
                                        val parentPeriod = if (parentChanged) "[2026-01-01,2026-07-01)" else "[2026-01-01,2027-01-01)"
                                        val childCount = if (parentChanged) 0 else 1
                                        val historyCount = if (parentChanged) 1 else 0
                                        val currentValid = admin.scalar("""
                                            SELECT (SELECT count(*)=1 AND bool_and(valid_period='$parentPeriod'::daterange) FROM $schema.contract)
                                            AND (SELECT count(*)=$childCount AND COALESCE(bool_and(price=100 AND valid_period='[2026-08-01,2026-09-01)'::daterange),true) FROM $schema.contract_price)
                                            AND NOT EXISTS (SELECT FROM $schema.contract_price p WHERE NOT COALESCE((SELECT range_agg(c.valid_period) @> p.valid_period FROM $schema.contract c WHERE c.contract_id=p.contract_id),false))
                                        """.trimIndent()) == "t"
                                        val historyValid = admin.scalar("""
                                            SELECT (SELECT count(*)=$historyCount AND COALESCE(bool_and(valid_period='[2026-01-01,2027-01-01)'::daterange AND NOT isempty(system_period) AND NOT upper_inf(system_period)),true) FROM $schema.contract_history)
                                            AND (SELECT count(*)=0 FROM $schema.contract_price_history)
                                            AND NOT EXISTS (SELECT FROM $schema.contract_history h JOIN $schema.contract c USING(row_id) WHERE upper(h.system_period) <> lower(c.system_period))
                                        """.trimIndent()) == "t"
                                        val ok = outcome == expected && currentValid && historyValid
                                        val key = "$approach,${if(parentFirst) "parent" else "child"},${if(commitFirst) "commit" else "rollback"}"
                                        raw.appendLine("${index+1},$key,Lock,$event,true,$outcome,$expected,$currentValid,$historyValid,${if(ok) "ok" else "failed"}")
                                        raw.flush()
                                        check(ok) { "Unexpected outcome or state: $key outcome=$outcome" }
                                        counts[key] = (counts[key] ?: 0) + 1
                                    } finally {
                                        first.rollback() // Also releases locks on observer failure.
                                        executor.shutdown()
                                        if (!executor.awaitTermination(35, TimeUnit.SECONDS)) {
                                            second.abort(java.util.concurrent.Executor { it.run() })
                                            executor.shutdownNow()
                                        }
                                    }
                                }
                            }
                        } finally {
                            admin.exec("DROP SCHEMA $schema CASCADE")
                        }
                    }
                }
                println("Repetition ${index+1}/$repetitions: all 8 cases passed")
            }
        }
        Files.newBufferedWriter(output.resolve("summary.csv")).use { out ->
            out.appendLine("approach,first_operation,first_end,passed")
            counts.forEach { (key, count) -> out.appendLine("$key,$count") }
        }
        println("Completed: ${output.toAbsolutePath()}")
    }
}
