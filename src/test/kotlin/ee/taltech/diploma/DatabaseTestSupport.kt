package ee.taltech.diploma

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.util.UUID

class TestDatabase(val approach: String, val schema: String) {
    fun connect(): Connection = Database.connect(approach, schema)
}

// Each invocation owns an isolated schema. Public benchmark data is untouched.
fun withDatabase(approach: String, test: (TestDatabase) -> Unit) {
    val schema = "test_" + UUID.randomUUID().toString().replace("-", "")
    Database.connect(approach).use { admin ->
        admin.execute("CREATE SCHEMA $schema")
        try {
            val database = TestDatabase(approach, schema)
            database.connect().use { connection ->
                connection.autoCommit = false
                for (file in listOf("common.sql.inc", "$approach.sql.inc", "operations.sql.inc")) {
                    connection.execute(Files.readString(Path.of("sql", file)))
                }
                connection.commit()
            }
            test(database)
        } finally {
            admin.execute("DROP SCHEMA $schema CASCADE")
        }
    }
}

fun Connection.execute(sql: String) {
    createStatement().use { it.execute(sql) }
}

fun Connection.scalar(sql: String): String = createStatement().use { statement ->
    statement.executeQuery(sql).use { rows ->
        check(rows.next()) { "Expected one result row" }
        rows.getString(1)
    }
}

fun Connection.contract(period: String = "[2026-01-01,2027-01-01)", id: Long = 1) {
    prepareStatement("INSERT INTO contract(contract_id, valid_period) VALUES (?, ?::daterange)").use {
        it.setLong(1, id)
        it.setString(2, period)
        it.executeUpdate()
    }
}

fun Connection.price(period: String = "[2026-01-01,2026-02-01)", amount: Int = 100, id: Long = 1) {
    prepareStatement("INSERT INTO contract_price(contract_id, price, valid_period) VALUES (?, ?, ?::daterange)").use {
        it.setLong(1, id)
        it.setInt(2, amount)
        it.setString(3, period)
        it.executeUpdate()
    }
}
