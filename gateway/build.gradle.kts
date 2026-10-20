plugins {
    kotlin("jvm") version "1.9.23"
    kotlin("plugin.spring") version "1.9.23"
    // Classes marked @PaidOperation are opened too, so that Spring can proxy them (see guard/PaidOperationGuard.kt).
    kotlin("plugin.allopen") version "1.9.23"
    id("org.springframework.boot") version "3.3.4"
    id("io.spring.dependency-management") version "1.1.6"
}

group = "com.dictara"
version = "1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.16.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.postgresql:postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers") {
        exclude(group = "org.testcontainers")
    }
    testImplementation("org.wiremock:wiremock-standalone:3.9.1")
    testImplementation("org.testcontainers:testcontainers:1.21.4")
    testImplementation("org.testcontainers:postgresql:1.21.4")
    testImplementation("org.testcontainers:junit-jupiter:1.21.4")
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // The @PaidOperation guard is an aspect.
    implementation("org.springframework.boot:spring-boot-starter-aop")
    // Rules about how the code is arranged (the mcp package stays apart, paid operations are marked), run as tests.
    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")
    // The official MCP client, to check that our own implementation of the server side speaks to a real one.
    testImplementation("io.modelcontextprotocol.sdk:mcp-core:2.1.0")
    testImplementation("io.modelcontextprotocol.sdk:mcp-json-jackson2:2.1.0")
    // Restarts the app when classes are rebuilt during `bootRun`; not part of the production jar.
    developmentOnly("org.springframework.boot:spring-boot-devtools")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("com.google.auth:google-auth-library-oauth2-http:1.23.0")
    implementation("com.google.cloud:google-cloud-storage:2.+")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        showStandardStreams = true
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }

    // Forward TEST_DB_* vars from root .env to the test JVM for local runs.
    // CI has no .env → Testcontainers is used instead.
    if (System.getenv("TEST_DB_URL") == null) {
        val dotEnv = rootProject.file("../.env")
        if (dotEnv.exists()) {
            dotEnv.readLines()
                .filter { it.startsWith("TEST_DB_") && it.contains("=") }
                .forEach { line ->
                    val key = line.substringBefore("=").trim()
                    val value = line.substringAfter("=").trim().removeSurrounding("\"")
                    environment(key, value)
                }
        }
    }
}

allOpen {
    annotation("com.dictara.gateway.guard.PaidOperation")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

