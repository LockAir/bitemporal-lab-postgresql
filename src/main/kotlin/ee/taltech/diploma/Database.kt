package ee.taltech.diploma

import java.sql.Connection
import java.sql.DriverManager

object Database {
    fun connect(approach: String, schema: String = "public"): Connection {
        require(approach in setOf("approach_a", "approach_b"))
        require(schema.matches(Regex("[a-z][a-z0-9_]*")))
        val host = System.getenv("DB_HOST") ?: "localhost"
        val port = System.getenv("DB_PORT") ?: "5432"
        val user = System.getenv("DB_USER") ?: "diploma"
        val password = System.getenv("DB_PASSWORD") ?: "diploma"
        val connection = DriverManager.getConnection(
            "jdbc:postgresql://$host:$port/$approach", user, password
        )
        try {
            connection.createStatement().use {
                it.execute("SET search_path TO $schema, public")
                it.execute("SET timezone TO 'UTC'")
                it.execute("SET statement_timeout TO '30s'")
            }
            return connection
        } catch (failure: Exception) {
            connection.close()
            throw failure
        }
    }
}
