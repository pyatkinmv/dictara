package com.dictara.gateway.config

import org.postgresql.util.PGobject
import org.springframework.context.annotation.Configuration
import org.springframework.core.convert.converter.Converter
import org.springframework.data.convert.ReadingConverter
import org.springframework.data.jdbc.repository.config.AbstractJdbcConfiguration
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories

@Configuration
@EnableJdbcRepositories(basePackages = ["com.dictara.gateway.repository"])
class JdbcConfig : AbstractJdbcConfiguration() {

    override fun userConverters(): List<Any> = listOf(PgObjectToStringConverter)

    @ReadingConverter
    object PgObjectToStringConverter : Converter<PGobject, String> {
        override fun convert(source: PGobject): String = source.value ?: ""
    }
}
