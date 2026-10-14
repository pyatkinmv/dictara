package com.dictara.gateway.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.SpringApplication
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment

class LocalProfileGuardTest {

    private val okUrl = "jdbc:postgresql://localhost:5432/dictara_dev"

    @Test
    fun `the development database on localhost and no bucket is fine`() {
        assertThat(LocalProfileGuard.check(okUrl, "")).isEmpty()
        assertThat(LocalProfileGuard.check(okUrl, null)).isEmpty()
        assertThat(LocalProfileGuard.check("jdbc:postgresql://127.0.0.1:55432/dictara_dev?ssl=false", "")).isEmpty()
    }

    @Test
    fun `a database on another host is refused`() {
        val problems = LocalProfileGuard.check("jdbc:postgresql://db.example.com:5432/dictara_dev", "")
        assertThat(problems).singleElement().asString().contains("db.example.com")
    }

    @Test
    fun `a tunnel to the production database is refused even on localhost`() {
        // An SSH tunnel shows up as localhost, but the database has the production name.
        val problems = LocalProfileGuard.check("jdbc:postgresql://localhost:5433/dictara", "")
        assertThat(problems).singleElement().asString().contains("'dictara'").contains("dictara_dev")
    }

    @Test
    fun `every problem is reported`() {
        val problems = LocalProfileGuard.check("jdbc:postgresql://prod-host/dictara", "my-bucket")
        assertThat(problems).hasSize(3)
        assertThat(problems.last()).contains("GCS_UPLOADS_BUCKET").contains("my-bucket")
    }

    @Test
    fun `a bucket alone is refused`() {
        assertThat(LocalProfileGuard.check(okUrl, "prod-audio")).singleElement().asString().contains("GCS_UPLOADS_BUCKET")
    }

    @Test
    fun `an unreadable or missing URL is refused`() {
        assertThat(LocalProfileGuard.check(null, "")).hasSize(1)
        assertThat(LocalProfileGuard.check("", "")).hasSize(1)
        assertThat(LocalProfileGuard.check("postgres://localhost/dictara_dev", "")).hasSize(1)
        // Several hosts could hide a real one behind a local one.
        assertThat(LocalProfileGuard.check("jdbc:postgresql://localhost,prod-host/dictara_dev", "")).hasSize(1)
    }

    // ── As the environment post-processor Spring Boot runs ─────────────────────

    private fun environment(profile: String?, vararg props: Pair<String, String>): StandardEnvironment {
        val env = StandardEnvironment()
        env.propertySources.addFirst(MapPropertySource("test", props.toMap()))
        if (profile != null) env.setActiveProfiles(profile)
        return env
    }

    @Test
    fun `startup fails under the local profile with a bad database`() {
        val env = environment("local", "spring.datasource.url" to "jdbc:postgresql://prod-host:5432/dictara")
        assertThatThrownBy { LocalProfileGuard().postProcessEnvironment(env, SpringApplication()) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Refusing to start with the 'local' profile")
            .hasMessageContaining("prod-host")
    }

    @Test
    fun `startup fails under the local profile when a bucket is configured`() {
        val env = environment(
            "local",
            "spring.datasource.url" to "jdbc:postgresql://localhost:5432/dictara_dev",
            "dictara.storage.gcs.bucket" to "prod-audio",
        )
        assertThatThrownBy { LocalProfileGuard().postProcessEnvironment(env, SpringApplication()) }
            .hasMessageContaining("GCS_UPLOADS_BUCKET")
    }

    @Test
    fun `startup is fine under the local profile with the development database`() {
        val env = environment("local", "spring.datasource.url" to "jdbc:postgresql://localhost:5432/dictara_dev")
        LocalProfileGuard().postProcessEnvironment(env, SpringApplication())
    }

    @Test
    fun `other profiles are not checked at all`() {
        // Production has a real database and a real bucket.
        val env = environment(
            null,
            "spring.datasource.url" to "jdbc:postgresql://postgres:5432/dictara",
            "dictara.storage.gcs.bucket" to "prod-audio",
        )
        LocalProfileGuard().postProcessEnvironment(env, SpringApplication())
    }
}
