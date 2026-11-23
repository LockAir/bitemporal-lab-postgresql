# Bitemporality in PostgreSQL

Experimental code for the bachelor's thesis **A Comparative Analysis of Approaches to Implementing Bitemporality in PostgreSQL** by Ilja Surnõtšev, TalTech.

The project compares two implementations on PostgreSQL 18.7:

- **Approach A:** exclusion constraints, custom temporal referential-integrity triggers, and advisory locks.
- **Approach B:** `UNIQUE ... WITHOUT OVERLAPS` and temporal foreign keys using `PERIOD`.

Both use the same contract/price model, trigger-based transaction-time history, and partial-period price correction function. Valid time uses `daterange`; transaction time uses `tstzrange` with transaction-start timestamps. Intermediate changes within one transaction are not preserved. Writes use `READ COMMITTED`.

## Requirements

- Docker with Docker Compose
- JDK 21

## Run

Run commands from the project root. On Windows, replace `./gradlew` with `.\gradlew.bat`.

```bash
docker compose up -d --wait
./gradlew test
```

The first container initialization creates `approach_a` and `approach_b`. Default connection: `localhost:5432`, user/password `diploma`. Override with `DB_HOST`, `DB_PORT`, `DB_USER`, and `DB_PASSWORD`.

## Experiments

```bash
# Retroactive price corrections
./gradlew benchmark -Pcontracts=10000 -Pwarmup=200 -Piterations=1000 -Prounds=6

# Bitemporal queries
./gradlew benchmarkHistory -Pcontracts=10000 -Pwarmup=200 -Piterations=1000 -Prounds=6

# Controlled concurrent transactions
./gradlew concurrencyCheck -Prepetitions=20

# Additional queries, inserts, future changes, storage, and query plans
./gradlew benchmarkSupplement "-Pcontracts=10000,100000,1000000"
```

If an additional task is not registered, append its matching `*-task.gradle.kts.txt` block to `build.gradle.kts` once.

For the first two benchmarks, run each dataset size separately: 10,000, 100,000, and 1,000,000 contracts. Data is generated automatically; no manual CSV import is required. Results are written to `results/`. Large datasets take longer to prepare.

The original benchmarks rebuild the dataset each round; the supplementary benchmark reuses it across rounds. Analyze these series separately.

## Structure

- `sql/` — schemas, constraints, history triggers, and price corrections
- `src/main/kotlin/` — data generator and experiment runners
- `src/test/kotlin/` — automated correctness tests

This is a research prototype. Results apply to the tested workloads and environment, not to all bitemporal applications.
