package ee.taltech.diploma

import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.PreparedStatement
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.Instant
import java.util.Locale
import java.util.Random
import java.util.UUID
import kotlin.math.ceil

/** Supplementary protocol: one baseline per size/approach, repeated read batches,
 * then committed writes on disjoint IDs. Original benchmark results stay separate. */
object SupplementBenchmarkRunner {
    private val tables = listOf("contract", "contract_price", "contract_history", "contract_price_history")
    private val scenarios = listOf("current_state", "valid_time", "transaction_time", "bitemporal_before", "bitemporal_after", "bitemporal_outside")
    @JvmStatic fun main(args: Array<String>) {
        require(args.size <= 5) { "Arguments: sizes(comma-separated) warmup iterations rounds seed" }
        val sizes = (args.getOrNull(0) ?: "10000").split(',').map(String::toInt)
        val warmup = args.getOrNull(1)?.toInt() ?: 200
        val iterations = args.getOrNull(2)?.toInt() ?: 1000
        val rounds = args.getOrNull(3)?.toInt() ?: 6
        val seed = args.getOrNull(4)?.toLong() ?: 42L
        require(sizes.isNotEmpty() && sizes.all { it >= 10 } && sizes.distinct().size == sizes.size)
        require(warmup >= 0 && iterations > 0 && rounds > 0)
        val batch = Math.addExact(warmup, iterations)
        val extras = Math.multiplyExact(batch, rounds)
        listOf("common", "approach_a", "approach_b", "operations", "migrate_a_to_b").forEach {
            require(Files.isRegularFile(Path.of("sql", "$it.sql.inc"))) { "Run from project root" }
        }
        val run = UUID.randomUUID().toString().replace("-", "")
        val out = Path.of("results", "supplement_$run")
        Files.createDirectories(out.resolve("plans"))
        val meta = StringBuilder("Started: ${Instant.now()}\nsizes=$sizes warmup=$warmup iterations=$iterations rounds=$rounds seed=$seed\n")
        meta.appendLine("Protocol: one fresh baseline per size/approach; all reads before all writes; no retries.")
        meta.appendLine("Writes use disjoint IDs; future fixtures=$extras per approach, added AFTER baseline storage and read measurements.")
        meta.appendLine("SELECT timer includes execute and consumption; write timer includes execution and COMMIT. Binding, validation and file I/O excluded.")
        meta.appendLine("current_state = currently stored valid-time schedule, not price at wall-clock current_date.")
        meta.appendLine("transaction_time = complete schedule known BEFORE correction; valid_time = currently known price at 2025-02-15.")
        meta.appendLine("JVM=${System.getProperty("java.version")} OS=${System.getProperty("os.name")} arch=${System.getProperty("os.arch")}")
        meta.appendLine("Client available processors=${Runtime.getRuntime().availableProcessors()}; client JVM max heap=${Runtime.getRuntime().maxMemory()}; these are NOT Docker server limits.")
        Files.writeString(out.resolve("environment.txt"), meta)
        Files.writeString(out.resolve("status.txt"), "RUNNING\n")
        println("Results: ${out.toAbsolutePath()}")
        sourceInventory(out)
        Files.newBufferedWriter(out.resolve("raw.csv")).use { raw ->
          Files.newBufferedWriter(out.resolve("summary.csv")).use { summary ->
            raw.appendLine("contracts,round,approach,scenario,phase,operation,contract_id,elapsed_ns,status")
            summary.appendLine("contracts,round,approach,scenario,count,median_ms,p95_ms,min_ms,max_ms")
            try {
              migrationCheck(out, run)
              for ((sizeIndex, n) in sizes.withIndex()) {
                val schema = "supplement_${run}_$n"
                val created = mutableListOf<String>()
                val bounds = mutableMapOf<String, Pair<OffsetDateTime, OffsetDateTime>>()
                fun order(r: Int) = if ((r + sizeIndex) % 2 == 0) listOf("approach_a", "approach_b") else listOf("approach_b", "approach_a")
                try {
                    for (a in order(0)) {
                        Database.connect(a).use { c -> c.createStatement().use { it.execute("CREATE SCHEMA $schema") } }
                        created.add(a)
                        Database.connect(a, schema).use { c ->
                            install(c, a)
                            val start = System.nanoTime()
                            bounds[a] = prepareData(c, n)
                            meta.appendLine("$n $a baseline_prepare_seconds=${(System.nanoTime()-start)/1e9} boundaries=${bounds[a]}")
                            recordEnvironment(c, a, 0, meta)
                            storage(c, out, n, a, "baseline")
                            inventory(c, out, n, a)
                        }
                        Files.writeString(out.resolve("environment.txt"), meta)
                    }
                    // Read-only rounds reuse the same logical baseline, with scenario rotation.
                    repeat(rounds) { r ->
                        val random = Random(seed + r)
                        val ids = List(batch) { (random.nextInt(n / 10) + 1) * 10L }
                        for (a in order(r)) Database.connect(a, schema).use { c ->
                            for (offset in scenarios.indices) {
                                val scenario = scenarios[(offset+r)%scenarios.size]
                                val asOf = if (scenario == "transaction_time" || scenario == "bitemporal_before") bounds.getValue(a).first else bounds.getValue(a).second
                                readSamples(c, n, r+1, a, scenario, asOf, ids, warmup, raw, summary)
                            }
                        }
                    }
                    // Diagnostic plans run after the timed reads, not inside their samples.
                    for (a in order(0)) Database.connect(a, schema).use { c ->
                        for (s in scenarios) {
                            val asOf = if (s == "transaction_time" || s == "bitemporal_before") bounds.getValue(a).first else bounds.getValue(a).second
                            c.prepareStatement("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + query(s)).use { p ->
                                bindRead(p, s, 10, asOf)
                                p.executeQuery().use { rs -> check(rs.next()); Files.writeString(out.resolve("plans/${n}_${a}_$s.json"), rs.getString(1)) }
                            }
                        }
                    }
                    // Shared future date is derived from the DB clock, then recorded for reproduction.
                    val future = Database.connect("approach_a", schema).use { c ->
                        c.createStatement().use { s -> s.executeQuery("SELECT make_date(extract(year from current_date)::int + 1, 1, 1)").use { rs -> rs.next(); rs.getObject(1, LocalDate::class.java) } }
                    }
                    meta.appendLine("$n future_start=$future future_fixture_count=$extras")
                    Files.writeString(out.resolve("environment.txt"), meta)
                    for (a in order(0)) Database.connect(a, schema).use { c ->
                        c.autoCommit = false
                        c.prepareStatement("INSERT INTO contract(contract_id,valid_period) VALUES (?,daterange(?::date,?::date,'[)'))").use { parent ->
                          c.prepareStatement("INSERT INTO contract_price(contract_id,price,valid_period) VALUES (?,100,daterange(?::date,?::date,'[)'))").use { price ->
                            for (i in 1..extras) {
                                for (p in listOf(parent, price)) { p.setLong(1,n.toLong()+i);p.setObject(2,future);p.setObject(3,future.plusMonths(1));p.executeUpdate() }
                                if (i%1000==0) c.commit()
                            }
                          }
                        }
                        c.commit(); c.autoCommit=true
                        for (t in tables) c.createStatement().use { it.execute("ANALYZE $t") }
                        storage(c,out,n,a,"before_writes")
                    }
                    repeat(rounds) { r ->
                        for (a in order(r)) Database.connect(a,schema).use { c ->
                            val writes = if (r%2==0) listOf("insert_contract","future_change") else listOf("future_change","insert_contract")
                            for (s in writes) writeSamples(c,n,r+1,a,s,future,extras,batch,warmup,raw,summary)
                        }
                    }
                    for (a in order(0)) Database.connect(a,schema).use { c ->
                        checkFinal(c,n,extras)
                        storage(c,out,n,a,"after_writes")
                    }
                    println("Completed size $n")
                } finally {
                    for (a in created) Database.connect(a).use { c -> c.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") } }
                }
              }
              Files.writeString(out.resolve("status.txt"),"COMPLETED\n")
            } catch (e: Exception) {
                Files.writeString(out.resolve("status.txt"),"FAILED: ${e.javaClass.name}: ${e.message}\n")
                throw e
            }
          }
        }
        val archive=out.resolveSibling("${out.fileName}.zip")
        java.util.zip.ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
            Files.walk(out).use { paths -> paths.filter { Files.isRegularFile(it) }.sorted().forEach { file ->
                zip.putNextEntry(java.util.zip.ZipEntry(out.relativize(file).toString().replace('\\','/')))
                Files.copy(file,zip);zip.closeEntry()
            } }
        }
        println("Completed. Send this ZIP: ${archive.toAbsolutePath()}")
    }

    private fun query(s: String): String = when(s) {
        "current_state" -> "SELECT valid_period::text, price FROM contract_price WHERE contract_id=? ORDER BY valid_period"
        "valid_time" -> "SELECT valid_period::text, price FROM contract_price WHERE contract_id=? AND valid_period @> DATE '2025-02-15' ORDER BY valid_period"
        "transaction_time" -> "SELECT valid_period::text, price FROM contract_price_versions WHERE contract_id=? AND system_period @> ?::timestamptz ORDER BY valid_period"
        else -> "SELECT valid_period::text, price FROM contract_price_versions WHERE contract_id=? AND system_period @> ?::timestamptz AND valid_period @> DATE '${if(s=="bitemporal_outside") "2025-02-05" else "2025-02-15"}' ORDER BY valid_period"
    }
    private fun bindRead(p: PreparedStatement,s: String,id: Long,t: OffsetDateTime) {
        p.setLong(1,id)
        if(s !in listOf("current_state","valid_time")) p.setObject(2,t)
    }
    private fun expected(s: String,id: Long): List<Pair<String,Int>> {
        val base=(100+id%1000).toInt()
        val old=(0..4).map { m -> "[${LocalDate.of(2025,1,1).plusMonths(m.toLong())},${LocalDate.of(2025,1,1).plusMonths(m+1L)})" to base+m }
        val split=listOf("[2025-02-01,2025-02-10)" to base+1,"[2025-02-10,2025-02-20)" to 1500,"[2025-02-20,2025-03-01)" to base+1)
        return when(s) {
            "current_state" -> listOf(old[0])+split+old.drop(2)
            "transaction_time" -> old
            "bitemporal_before" -> listOf(old[1])
            "bitemporal_outside" -> listOf(split[0])
            else -> listOf(split[1])
        }
    }
    private fun readSamples(c:Connection,n:Int,r:Int,a:String,s:String,t:OffsetDateTime,ids:List<Long>,warm:Int,raw:BufferedWriter,summary:BufferedWriter) {
        val times=mutableListOf<Long>()
        c.prepareStatement(query(s)).use { p ->
            ids.forEachIndexed { i,id ->
                bindRead(p,s,id,t)
                val actual=mutableListOf<Pair<String,java.math.BigDecimal>>()
                val start=System.nanoTime()
                p.executeQuery().use { rs -> while(rs.next()) actual.add(rs.getString(1) to rs.getBigDecimal(2)) }
                val elapsed=System.nanoTime()-start
                val want=expected(s,id)
                val ok=actual.size==want.size && actual.zip(want).all { (x,y) -> x.first==y.first && x.second.compareTo(y.second.toBigDecimal())==0 }
                sample(raw,n,r,a,s,i,id,elapsed,warm,ok)
                check(ok) { "$a $s id=$id: $actual != $want" }
                if(i>=warm) times.add(elapsed)
            }
        }
        raw.flush(); summarize(summary,n,r,a,s,times)
    }
    private fun writeSamples(c:Connection,n:Int,r:Int,a:String,s:String,future:LocalDate,extras:Int,batch:Int,warm:Int,raw:BufferedWriter,summary:BufferedWriter) {
        val times=mutableListOf<Long>()
        c.autoCommit=false
        c.prepareStatement("INSERT INTO contract(contract_id,valid_period) VALUES (?,'[2025-01-01,2025-06-01)')").use { parent ->
          c.prepareStatement("""INSERT INTO contract_price(contract_id,price,valid_period)
              SELECT ?,100+month,daterange((DATE '2025-01-01'+month*INTERVAL '1 month')::date,
              (DATE '2025-01-01'+(month+1)*INTERVAL '1 month')::date,'[)') FROM generate_series(0,4) month""").use { price ->
           c.prepareStatement("SELECT correct_price(?,daterange(?::date,?::date,'[)'),200)").use { change ->
            repeat(batch) { i ->
                val id=n.toLong()+(r-1L)*batch+i+1 + if(s=="insert_contract") extras else 0
                parent.setLong(1,id);price.setLong(1,id)
                change.setLong(1,id);change.setObject(2,future.plusDays(9));change.setObject(3,future.plusDays(19))
                val start=System.nanoTime()
                try {
                    if(s=="insert_contract") { check(parent.executeUpdate()==1);check(price.executeUpdate()==5) }
                    else change.executeQuery().use { rs -> while(rs.next()) rs.getObject(1) }
                    c.commit()
                } catch(e:Exception) { c.rollback();sample(raw,n,r,a,s,i,id,System.nanoTime()-start,warm,false);raw.flush();throw e }
                val elapsed=System.nanoTime()-start
                // Validation uses a new transaction and ends it before the next timed operation.
                val ok=validateWrite(c,s,id,future)
                c.commit()
                sample(raw,n,r,a,s,i,id,elapsed,warm,ok)
                check(ok) { "$a $s id=$id failed current/history validation" }
                if(i>=warm) times.add(elapsed)
            }
           }
          }
        }
        c.autoCommit=true;raw.flush();summarize(summary,n,r,a,s,times)
    }
    private fun validateWrite(c:Connection,s:String,id:Long,future:LocalDate):Boolean {
        val want=if(s=="insert_contract") (0..4).map { m -> "[${LocalDate.of(2025,1,1).plusMonths(m.toLong())},${LocalDate.of(2025,1,1).plusMonths(m+1L)})" to 100+m }
            else listOf("[$future,${future.plusDays(9)})" to 100,"[${future.plusDays(9)},${future.plusDays(19)})" to 200,"[${future.plusDays(19)},${future.plusMonths(1)})" to 100)
        val actual=mutableListOf<Pair<String,java.math.BigDecimal>>()
        c.prepareStatement(query("current_state")).use { p -> p.setLong(1,id);p.executeQuery().use { rs -> while(rs.next()) actual.add(rs.getString(1) to rs.getBigDecimal(2)) } }
        if(actual.size!=want.size || !actual.zip(want).all { (x,y)->x.first==y.first && x.second.compareTo(y.second.toBigDecimal())==0 }) return false
        c.prepareStatement("""SELECT (SELECT count(*) FROM contract WHERE contract_id=?),
            (SELECT count(*) FROM contract_price_history WHERE contract_id=?),
            (SELECT count(*) FROM contract_price_history h WHERE contract_id=? AND price=100
             AND valid_period=daterange(?::date,?::date,'[)') AND NOT isempty(system_period)
             AND upper(system_period)=(SELECT min(lower(system_period)) FROM contract_price WHERE contract_id=?))""").use { p ->
            p.setLong(1,id);p.setLong(2,id);p.setLong(3,id);p.setObject(4,future);p.setObject(5,future.plusMonths(1));p.setLong(6,id)
            p.executeQuery().use { rs -> rs.next();return rs.getInt(1)==1 && rs.getInt(2)==(if(s=="insert_contract") 0 else 1) && rs.getInt(3)==(if(s=="insert_contract") 0 else 1) }
        }
    }
    private fun sample(w:BufferedWriter,n:Int,r:Int,a:String,s:String,i:Int,id:Long,ns:Long,warm:Int,ok:Boolean) {
        w.appendLine("$n,$r,$a,$s,${if(i<warm) "warmup" else "measure"},$i,$id,$ns,${if(ok) "ok" else "error"}")
    }
    private fun summarize(w:BufferedWriter,n:Int,r:Int,a:String,s:String,samples:List<Long>) {
        val sorted=samples.sorted();val m=sorted.size/2
        val median=if(sorted.size%2==0) (sorted[m-1].toDouble()+sorted[m])/2 else sorted[m].toDouble()
        fun ms(x:Double)=String.format(Locale.ROOT,"%.6f",x/1e6)
        w.appendLine("$n,$r,$a,$s,${sorted.size},${ms(median)},${ms(sorted[ceil(sorted.size*.95).toInt()-1].toDouble())},${ms(sorted.first().toDouble())},${ms(sorted.last().toDouble())}")
        w.flush();println("$n $a round $r $s median=${ms(median)} ms")
    }
    private fun checkFinal(c:Connection,n:Int,k:Int) {
        val expected=listOf(n.toLong()+2L*k,5L*n+2L*(n/10)+8L*k,0L,n/10L+k)
        tables.zip(expected).forEach { (t,want) -> c.createStatement().use { s -> s.executeQuery("SELECT count(*) FROM $t").use { rs -> rs.next();check(rs.getLong(1)==want) { "$t: expected $want got ${rs.getLong(1)}" } } } }
    }
    private fun appendCsv(out:Path,name:String,header:String,rows:List<List<String>>) {
        val p=out.resolve(name);val exists=Files.exists(p)
        Files.newBufferedWriter(p,java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.APPEND).use { w ->
            if(!exists) w.appendLine(header)
            for(row in rows) w.appendLine(row.joinToString(",") { "\""+it.replace("\"","\"\"")+"\"" })
        }
    }
    private fun storage(c:Connection,out:Path,n:Int,a:String,stage:String) {
        val data=tables.map { t ->
            c.createStatement().use { s -> s.executeQuery("SELECT count(*),pg_table_size('$t'::regclass),pg_indexes_size('$t'::regclass),pg_total_relation_size('$t'::regclass) FROM $t").use { rs -> rs.next();listOf(n.toString(),a,stage,t)+(1..4).map { rs.getString(it) } } }
        }
        appendCsv(out,"storage.csv","contracts,approach,stage,table,rows,table_bytes,index_bytes,total_bytes",data)
    }
    private fun inventory(c:Connection,out:Path,n:Int,a:String) {
        val queries=mapOf(
            "constraint" to "SELECT conname,contype::text,pg_get_constraintdef(oid) FROM pg_constraint WHERE connamespace=current_schema()::regnamespace ORDER BY conname",
            "trigger" to "SELECT tgname,tgisinternal::text,pg_get_triggerdef(t.oid) FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid WHERE c.relnamespace=current_schema()::regnamespace ORDER BY c.relname,tgname",
            "function" to "SELECT proname,'user',pg_get_function_identity_arguments(oid) FROM pg_proc WHERE pronamespace=current_schema()::regnamespace AND NOT EXISTS (SELECT 1 FROM pg_depend d WHERE d.classid='pg_proc'::regclass AND d.objid=pg_proc.oid AND d.deptype='e') ORDER BY proname")
        for((kind,q) in queries) {
            val rows=mutableListOf<List<String>>()
            c.createStatement().use { s -> s.executeQuery(q).use { rs -> while(rs.next()) rows.add(listOf(n.toString(),a,kind,rs.getString(1),rs.getString(2),rs.getString(3))) } }
            appendCsv(out,"objects.csv","contracts,approach,kind,name,type_or_internal,definition",rows)
        }
    }
    private fun sourceInventory(out:Path) {
        val rows=listOf("common","operations","approach_a","approach_b","migrate_a_to_b").map { name ->
            val text=Files.readString(Path.of("sql","$name.sql.inc"))
            val hash=java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
            Files.createDirectories(out.resolve("sql_snapshot"));Files.writeString(out.resolve("sql_snapshot/$name.sql.inc"),text)
            listOf("$name.sql.inc",text.lineSequence().count { it.isNotBlank() && !it.trimStart().startsWith("--") }.toString(),hash)
        }
        appendCsv(out,"source.csv","file,nonblank_noncomment_lines,sha256",rows)
    }
    private fun migrationCheck(out:Path,run:String) {
        val schema="supplement_migration_$run"
        Database.connect("approach_a").use { c -> c.createStatement().use { it.execute("CREATE SCHEMA $schema") } }
        try {
            Database.connect("approach_a",schema).use { c ->
                install(c,"approach_a")
                val (before,after)=prepareData(c,10)
                fun digest():List<String> = tables.map { t ->
                    c.createStatement().use { p -> p.executeQuery("SELECT md5(string_agg(row_to_json(x)::text, ',' ORDER BY row_to_json(x)::text)) FROM $t x").use { rs -> rs.next();rs.getString(1) ?: "empty" } }
                }
                val original=digest()
                c.autoCommit=false
                try {
                    c.createStatement().use { it.execute(Files.readString(Path.of("sql","migrate_a_to_b.sql.inc"))) }
                    check(digest()==original) { "Migration changed data/history" }
                    c.commit()
                } catch(e:Exception) { c.rollback();throw e }
                c.autoCommit=true
                for(s in scenarios) c.prepareStatement(query(s)).use { p ->
                    bindRead(p,s,10,if(s=="transaction_time" || s=="bitemporal_before") before else after)
                    val actual=mutableListOf<Pair<String,java.math.BigDecimal>>()
                    p.executeQuery().use { rs -> while(rs.next()) actual.add(rs.getString(1) to rs.getBigDecimal(2)) }
                    val want=expected(s,10)
                    check(actual.size==want.size && actual.zip(want).all { (x,y)->x.first==y.first && x.second.compareTo(y.second.toBigDecimal())==0 })
                }
                fun rejected(sql:String,state:String) {
                    try { c.createStatement().use { it.execute(sql) };error("Migration constraint did not reject: $sql") }
                    catch(e:java.sql.SQLException) { check(e.sqlState==state) { "Expected $state got ${e.sqlState}" } }
                }
                rejected("INSERT INTO contract(contract_id,valid_period) VALUES (1,'[2025-01-01,2025-02-01)')","23P01")
                rejected("INSERT INTO contract_price(contract_id,price,valid_period) VALUES (999,100,'[2025-01-01,2025-02-01)')","23503")
                rejected("UPDATE contract SET valid_period='[2025-01-01,2025-02-01)' WHERE contract_id=1","23503")
                check(digest()==original)
                Files.writeString(out.resolve("migration.txt"),"PASS: isolated 10-contract A-to-B migration; 4 declared logical steps, 12 DDL statements, one transaction.\nAll four table contents unchanged, six read scenarios verified, overlap/missing-parent/parent-shortening rejected.\nNo production migration, duration or online availability claim. Common history and correct_price stay unchanged.\n")
            }
        } finally { Database.connect("approach_a").use { c -> c.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") } } }
    }

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
