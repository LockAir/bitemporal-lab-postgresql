plugins {
    kotlin("jvm") version "2.2.21"
    application
}

repositories { mavenCentral() }

dependencies {
    implementation("org.postgresql:postgresql:42.7.8")
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin { jvmToolchain(21) }
application { mainClass.set("ee.taltech.diploma.DataGeneratorKt") }
tasks.test {
    useJUnitPlatform()
    maxParallelForks = 1
    testLogging { events("passed", "skipped", "failed") }
}

tasks.register<JavaExec>("benchmark") {
    group = "application"
    description = "Measure retroactive corrections for approaches A and B"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("ee.taltech.diploma.BenchmarkRunner")
    workingDir = projectDir
    args(
        providers.gradleProperty("contracts").getOrElse("10000"),
        providers.gradleProperty("warmup").getOrElse("20"),
        providers.gradleProperty("iterations").getOrElse("200"),
        providers.gradleProperty("rounds").getOrElse("4"),
        providers.gradleProperty("seed").getOrElse("42")
    )
}

tasks.register<JavaExec>("benchmarkHistory") {
    group = "application"
    description = "Validate and measure bitemporal price queries"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("ee.taltech.diploma.HistoryBenchmarkRunner")
    workingDir = projectDir
    args(
        providers.gradleProperty("contracts").getOrElse("10000"),
        providers.gradleProperty("warmup").getOrElse("200"),
        providers.gradleProperty("iterations").getOrElse("1000"),
        providers.gradleProperty("rounds").getOrElse("6"),
        providers.gradleProperty("seed").getOrElse("42")
    )
}

tasks.register<JavaExec>("concurrencyCheck") {
    group = "verification"
    description = "Check controlled concurrent parent/child conflicts and rollback history"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("ee.taltech.diploma.ConcurrencyRunner")
    workingDir = projectDir
    args(providers.gradleProperty("repetitions").getOrElse("20"))
}

tasks.register<JavaExec>("benchmarkSupplement") {
    group = "application"
    description = "Missing write/read/storage/plan measurements; one baseline per size"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("ee.taltech.diploma.SupplementBenchmarkRunner")
    workingDir = projectDir
    args(
        providers.gradleProperty("contracts").getOrElse("10000"),
        providers.gradleProperty("warmup").getOrElse("200"),
        providers.gradleProperty("iterations").getOrElse("1000"),
        providers.gradleProperty("rounds").getOrElse("6"),
        providers.gradleProperty("seed").getOrElse("42")
    )
}
