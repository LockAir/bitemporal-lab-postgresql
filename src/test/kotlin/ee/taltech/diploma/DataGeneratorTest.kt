package ee.taltech.diploma

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class DataGeneratorTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `same seed produces identical files with expected counts`() {
        val first = temporary.resolve("first")
        val second = temporary.resolve("second")
        generateData(1001, 42, first)
        generateData(1001, 42, second)
        Files.list(first).use { paths ->
            paths.forEach { file ->
                assertArrayEquals(Files.readAllBytes(file), Files.readAllBytes(second.resolve(file.fileName)))
            }
        }
        assertEquals(1001, Files.readAllLines(first.resolve("batch_00000_contracts.csv")).size)
        assertEquals(5001, Files.readAllLines(first.resolve("batch_00000_prices.csv")).size)
        assertEquals(2, Files.readAllLines(first.resolve("batch_00001_contracts.csv")).size)
        assertEquals(101, Files.readAllLines(first.resolve("corrections.csv")).size)
    }
}
