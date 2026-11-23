package ee.taltech.diploma

import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.util.Random
import kotlin.io.path.bufferedWriter

// Each shard contains at most 1,000 contracts and 5,000 prices.
// Load one shard in one transaction to bound advisory locks and memory use.
fun generateData(count: Int, seed: Long, directory: Path) {
    require(count > 0)
    require(!Files.exists(directory)) { "Output directory already exists: $directory" }
    Files.createDirectories(directory)
    val random = Random(seed)
    directory.resolve("corrections.csv").bufferedWriter().use { corrections ->
        corrections.appendLine("contract_id,valid_from,valid_to,price")
        var firstId = 1
        var batch = 0
        while (firstId <= count) {
            val prefix = "batch_%05d".format(batch)
            directory.resolve("${prefix}_contracts.csv").bufferedWriter().use { contracts ->
                directory.resolve("${prefix}_prices.csv").bufferedWriter().use { prices ->
                    contracts.appendLine("contract_id,valid_period")
                    prices.appendLine("contract_id,price,valid_period")
                    val lastId = minOf(firstId + 999, count)
                    for (id in firstId..lastId) {
                        val start = LocalDate.of(2020, 1, 1).plusDays(random.nextInt(3650).toLong())
                        contracts.appendLine("$id,\"[$start,${start.plusMonths(5)})\"")
                        repeat(5) { month ->
                            val from = start.plusMonths(month.toLong())
                            val to = start.plusMonths(month.toLong() + 1)
                            val price = BigDecimal.valueOf(1000L + random.nextInt(99001), 2)
                            prices.appendLine("$id,$price,\"[$from,$to)\"")
                        }
                        // Exactly floor(count / 10) contracts get one correction.
                        if (id % 10 == 0) {
                            val price = BigDecimal.valueOf(1000L + random.nextInt(99001), 2)
                            corrections.appendLine("$id,${start.plusDays(5)},${start.plusDays(15)},$price")
                        }
                    }
                }
            }
            firstId += 1000
            batch++
        }
    }
    directory.resolve("dataset.txt").toFile().writeText(
        "generator=1\nseed=$seed\ncontracts=$count\nprices=${count.toLong() * 5}\n" +
            "corrections=${count / 10}\nbatch_size=1000\n"
    )
}

fun main(args: Array<String>) {
    require(args.size == 3) { "Usage: <contract-count> <seed> <new-output-directory>" }
    generateData(args[0].toInt(), args[1].toLong(), Path.of(args[2]))
    println("Generated dataset in ${args[2]}")
}
