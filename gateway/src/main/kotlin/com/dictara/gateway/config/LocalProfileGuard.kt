package com.dictara.gateway.config

import org.springframework.boot.SpringApplication
import org.springframework.boot.env.EnvironmentPostProcessor
import org.springframework.core.Ordered
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.Profiles

/**
 * Safety net for the `local` profile, which seeds data, runs extra migrations and deletes things in
 * storage: it refuses to start unless it is clearly pointed at the development database and no bucket.
 *
 * This runs as an [EnvironmentPostProcessor] on purpose: it has to fail before Flyway touches the
 * database, and by the time any bean exists the migrations (and the seed) would already have run.
 * The rules cannot be switched off.
 */
class LocalProfileGuard : EnvironmentPostProcessor, Ordered {

    // After the application*.yaml files are loaded, so the checked values are the final ones.
    override fun getOrder() = Ordered.LOWEST_PRECEDENCE

    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) {
        if (!environment.acceptsProfiles(Profiles.of("local"))) return
        val problems = check(
            datasourceUrl = environment.getProperty("spring.datasource.url"),
            gcsBucket = environment.getProperty("dictara.storage.gcs.bucket"),
        )
        if (problems.isNotEmpty()) {
            throw IllegalStateException(
                "Refusing to start with the 'local' profile:\n" + problems.joinToString("\n") { " - $it" }
            )
        }
    }

    companion object {
        const val DEV_DATABASE = "dictara_dev"
        private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1")
        private val URL = Regex("""^jdbc:postgresql://([^/:?,]+)(?::\d+)?/([^?;]+)""")

        /** What is wrong with these settings for a local run; empty if nothing is. */
        fun check(datasourceUrl: String?, gcsBucket: String?): List<String> {
            val problems = mutableListOf<String>()
            val match = datasourceUrl?.let { URL.find(it) }
            if (match == null) {
                problems += "the database URL '$datasourceUrl' is not a plain jdbc:postgresql://host[:port]/name URL"
            } else {
                val (host, name) = match.destructured
                if (host !in LOCAL_HOSTS) {
                    problems += "the database host is '$host'; only ${LOCAL_HOSTS.joinToString(" or ")} is allowed " +
                        "(a tunnel to a real database must not be used with this profile)"
                }
                if (name != DEV_DATABASE) {
                    problems += "the database is '$name'; only '$DEV_DATABASE' is allowed"
                }
            }
            if (!gcsBucket.isNullOrBlank()) {
                problems += "GCS_UPLOADS_BUCKET is set ('$gcsBucket'); storage cleanup could delete real audio, " +
                    "unset it (audio is kept in the database under this profile)"
            }
            return problems
        }
    }
}
