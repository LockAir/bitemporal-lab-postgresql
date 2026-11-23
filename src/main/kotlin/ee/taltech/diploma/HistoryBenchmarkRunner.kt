package ee.taltech.diploma

import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.util.Locale
import java.util.Random
import java.util.UUID
import kotlin.math.ceil

/** Read-only bitemporal point queries with independently checked expected prices. */
object HistoryBenchmarkRunner {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 5) { "Arguments: contracts warmup iterations rounds seed" }
        val contracts = args.getOrNull(0)?.toInt() ?: 10_000
        val warmup = args.getOrNull(1)?.toInt() ?: 200
        val iterations = args.getOrNull(2)?.toInt() ?: 1000
        val rounds = args.getOrNull(3)?.toInt() ?: 6
        val seed = args.getOrNull(4)?.toLong() ?: 42L
        require(contracts > 0 && warmup >= 0 && iterations > 0 && rounds > 0)
        require(contracts >= 10) { "Need at least one corrected contract" }
        val sqlFiles = listOf("common.sql.inc", "approach_a.sql.inc", "approach_b.sql.inc", "operations.sql.inc")
        sqlFiles.forEach { require(Files.isRegularFile(Path.of("sql", it))) { "Run from the project root: missing sql/$it" } }
        val runId = UUID.randomUUID().toString().replace("-", "")
        val output = Path.of("results", "history_$runId")
        Files.createDirectories(output)
        val metadata = StringBuilder().apply {
            appendLine("Started: ${Instant.now()}")
            appendLine("Scenario: bitemporal price lookup; before correction, after correction, outside corrected period")
            appendLine("contracts=$contracts warmup=$warmup iterations=$iterations rounds=$rounds seed=$seed")
            appendLine("JVM=${System.getProperty("java.version")} OS=${System.getProperty("os.name")} arch=${System.getProperty("os.arch")}")
            appendLine("Time includes SELECT execution and result consumption in auto-commit; excludes binding and answer validation")
            appendLine("Each round uses fresh schemas; order alternates A/B and B/A; no retries")
            appendLine("Median averages middle values; p95 is nearest-rank; summaries are per round")
        }
        Files.writeString(output.resolve("environment.txt"), metadata)
        println("Preparing $contracts contracts and ${contracts.toLong() * 5} prices per approach.")
        println("Results: ${output.toAbsolutePath()}")
        Files.newBufferedWriter(output.resolve("raw.csv")).use { raw ->
            Files.newBufferedWriter(output.resolve("summary.csv")).use { summary ->
                raw.appendLine("round,approach,scenario,phase,operation,contract_id,elapsed_ns,status,sql_state")
                summary.appendLine("round,approach,scenario,count,median_ms,p95_ms,min_ms,max_ms")
                repeat(rounds) { roundIndex ->
                    val round = roundIndex + 1
                    val schema = "history_${runId}_$round"
                    val approaches = if (roundIndex % 2 == 0) listOf("approach_a", "approach_b") else listOf("approach_b", "approach_a")
                    val random = Random(seed + roundIndex)
                    val workload = List(warmup + iterations) { (random.nextInt(contracts / 10) + 1) * 10 }
                    val boundaries = mutableMapOf<String, Pair<java.time.OffsetDateTime, java.time.OffsetDateTime>>()
                    val created = mutableListOf<String>()
                    try {
                        for (approach in approaches) {
                            Database.connect(approach).use { admin ->
                                admin.createStatement().use { it.execute("CREATE SCHEMA $schema") }
                            }
                            created.add(approach)
                            Database.connect(approach, schema).use { connection ->
                                install(connection, approach)
                                boundaries[approach] = prepareData(connection, contracts)
                                metadata.appendLine("Round $round $approach boundaries=${boundaries.getValue(approach)}")
                                recordEnvironment(connection, approach, round, metadata)
                            }
                            println("Round $round/$rounds: $approach prepared.")
                        }
                        Files.writeString(output.resolve("environment.txt"), metadata)
                        for (approach in approaches) {
                            Database.connect(approach, schema).use { connection ->
                                val (before, after) = boundaries.getValue(approach)
                                // Rotate scenario order to avoid always measuring one case first.
                                val scenarios = listOf("before", "after", "outside")
                                for (offset in scenarios.indices) {
                                    val scenario = scenarios[(offset + roundIndex) % scenarios.size]
                                    val samples = measure(connection, workload, warmup, round, approach, scenario,
                                        if (scenario == "before") before else after, raw)
                                    val sorted = samples.sorted()
                                    val middle = sorted.size / 2
                                    val median = if (sorted.size % 2 == 0) {
                                        (sorted[middle - 1].toDouble() + sorted[middle]) / 2.0
                                    } else sorted[middle].toDouble()
                                    val p95 = sorted[ceil(sorted.size * 0.95).toInt() - 1].toDouble()
                                    val line = "$round,$approach,$scenario,${samples.size},${milliseconds(median)},${milliseconds(p95)},${milliseconds(sorted.first().toDouble())},${milliseconds(sorted.last().toDouble())}"
                                    summary.appendLine(line)
                                    summary.flush()
                                    println("$approach | $scenario | round $round | median ${milliseconds(median)} ms | p95 ${milliseconds(p95)} ms | ${samples.size} operations")
                                }
                                validateResults(connection, contracts, 0)
                            }
                        }
                    } finally {
                        // Drop only schemas created by this invocation, even after a failure.
                        for (approach in created) {
                            Database.connect(approach).use { admin ->
                                admin.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") }
                            }
                        }
                    }
                }
            }
        }
        println("Completed. See summary.csv and raw.csv in $output")
    }

    private fun milliseconds(nanos: Double): String = String.format(Locale.ROOT, "%.6f", nanos / 1_000_000.0)

    private fun install(connection: Connection, approach: String) {
        connection.autoCommit = false
        for (file in listOf("common.sql.inc", "$approach.sql.inc", "operations.sql.inc")) {
            connection.createStatement().use { it.execute(Files.readString(Path.of("sql", file))) }
        }
        connection.commit()
    }

    private fun prepareData(connection: Connection, contracts: Int): Pair<java.time.OffsetDateTime, java.time.OffsetDateTime> {
        // Bounded transactions also bound the number of A's advisory locks.
        connection.prepareStatement("""
            INSERT INTO contract(contract_id, valid_period)
            SELECT id, daterange(DATE '2025-01-01', DATE '2025-06-01', '[)')
            FROM generate_series(?::bigint, ?::bigint) AS id
        """.trimIndent()).use { insertContracts ->
            connection.prepareStatement("""
                INSERT INTO contract_price(contract_id, price, valid_period)
                SELECT id, 100 + id % 1000 + month,
                    daterange((DATE '2025-01-01' + month * INTERVAL '1 month')::date,
                              (DATE '2025-01-01' + (month + 1) * INTERVAL '1 month')::date, '[)')
                FROM generate_series(?::bigint, ?::bigint) AS id
                CROSS JOIN generate_series(0, 4) AS month
            """.trimIndent()).use { insertPrices ->
                var first = 1L
                while (first <= contracts.toLong()) {
                    val last = minOf(first + 999, contracts.toLong())
                    for (statement in listOf(insertContracts, insertPrices)) {
                        statement.setLong(1, first)
                        statement.setLong(2, last)
                        statement.executeUpdate()
                    }
                    connection.commit()
                    if (last % 10_000L == 0L || last == contracts.toLong()) println("Loaded $last/$contracts contracts")
                    first = last + 1
                }
            }
        }
        val before = timestamp(connection)
        connection.commit() // End the timestamp transaction before starting any correction.
        connection.prepareStatement("SELECT correct_price(?, '[2025-02-10,2025-02-20)', 1500)").use { correction ->
            var changes = 0
            var id = 10L
            while (id <= contracts.toLong()) {
                correction.setLong(1, id)
                correction.execute()
                changes++
                if (changes % 100 == 0) connection.commit()
                id += 10
            }
            connection.commit()
        }
        connection.autoCommit = true
        for (table in listOf("contract", "contract_price", "contract_history", "contract_price_history")) {
            connection.createStatement().use { it.execute("ANALYZE $table") }
        }
        validateResults(connection, contracts, 0)
        return before to timestamp(connection)
    }

    private fun recordEnvironment(connection: Connection, approach: String, round: Int, metadata: StringBuilder) {
        metadata.appendLine("Round $round $approach")
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT version()").use { rows ->
                rows.next()
                metadata.appendLine(rows.getString(1))
            }
            statement.executeQuery("""
                SELECT name, setting, unit FROM pg_settings
                WHERE name IN ('shared_buffers', 'work_mem', 'synchronous_commit', 'fsync',
                    'full_page_writes', 'jit', 'max_parallel_workers_per_gather', 'autovacuum',
                    'default_transaction_isolation', 'max_connections') ORDER BY name
            """.trimIndent()).use { rows ->
                while (rows.next()) metadata.appendLine("${rows.getString(1)}=${rows.getString(2)} ${rows.getString(3) ?: ""}")
            }
        }
    }

    private fun timestamp(connection: Connection): java.time.OffsetDateTime =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT clock_timestamp()").use { rows ->
                check(rows.next())
                rows.getObject(1, java.time.OffsetDateTime::class.java)
            }
        }

    private fun measure(
        connection: Connection, ids: List<Int>, warmup: Int, round: Int,
        approach: String, scenario: String, asOf: java.time.OffsetDateTime, raw: BufferedWriter
    ): List<Long> {
        val measured = mutableListOf<Long>()
        connection.autoCommit = true
        connection.prepareStatement("""
            SELECT price FROM contract_price_versions
            WHERE contract_id = ? AND valid_period @> ?::date AND system_period @> ?::timestamptz
        """.trimIndent()).use { statement ->
            for ((index, id) in ids.withIndex()) {
                val phase = if (index < warmup) "warmup" else "measure"
                statement.setLong(1, id.toLong())
                statement.setObject(2, java.time.LocalDate.parse(if (scenario == "outside") "2025-02-05" else "2025-02-15"))
                statement.setObject(3, asOf)
                val expected = java.math.BigDecimal(if (scenario == "after") 1500 else 101 + id % 1000)
                var count = 0
                var actual: java.math.BigDecimal? = null
                val started = System.nanoTime()
                val elapsed: Long
                try {
                    statement.executeQuery().use { result ->
                        while (result.next()) { actual = result.getBigDecimal(1); count++ }
                    }
                    elapsed = System.nanoTime() - started
                } catch (failure: SQLException) {
                    raw.appendLine("$round,$approach,$scenario,$phase,$index,$id,${System.nanoTime() - started},error,${failure.sqlState}")
                    raw.flush()
                    throw failure
                }
                val valid = count == 1 && actual?.compareTo(expected) == 0
                raw.appendLine("$round,$approach,$scenario,$phase,$index,$id,$elapsed,${if (valid) "ok" else "wrong_result"},")
                if (!valid) raw.flush()
                check(valid) { "$approach/$scenario id=$id: expected $expected, actual=$actual, rows=$count" }
                if (phase == "measure") measured.add(elapsed)
            }
        }
        raw.flush()
        return measured
    }

    private fun validateResults(connection: Connection, contracts: Int, corrections: Int) {
        connection.createStatement().use { statement ->
            statement.executeQuery("""
                SELECT (SELECT count(*) FROM contract),
                       (SELECT count(*) FROM contract_price),
                       (SELECT count(*) FROM contract_price_history),
                       (SELECT count(*) FROM contract_price WHERE price >= 2000),
                       (SELECT count(*) FROM contract_price WHERE price >= 2000
                            AND (valid_period <> '[2025-01-10,2025-01-20)'::daterange
                                 OR price <> 2000 + contract_id % 1000)),
                       (SELECT count(*) FROM contract_price_history WHERE isempty(system_period))
            """.trimIndent()).use { rows ->
                rows.next()
                check(rows.getLong(1) == contracts.toLong()) { "Unexpected contract count" }
                check(rows.getLong(2) == contracts.toLong() * 5 + 2L * (contracts / 10 + corrections)) { "Unexpected price count" }
                check(rows.getLong(3) == (contracts / 10 + corrections).toLong()) { "Unexpected history count" }
                check(rows.getLong(4) == corrections.toLong()) { "Missing corrections" }
                check(rows.getLong(5) == 0L && rows.getLong(6) == 0L) { "Incorrect prices or periods" }
            }
        }
    }
}
