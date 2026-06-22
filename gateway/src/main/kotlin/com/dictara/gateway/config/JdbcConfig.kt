package com.dictara.gateway.config

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.postgresql.util.PGobject
import org.springframework.context.annotation.Configuration
import org.springframework.core.convert.converter.Converter
import org.springframework.data.convert.ReadingConverter
import org.springframework.data.convert.WritingConverter
import org.springframework.data.jdbc.repository.config.AbstractJdbcConfiguration
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories

@Configuration
@EnableJdbcRepositories(basePackages = ["com.dictara.gateway.repository"])
class JdbcConfig(private val objectMapper: ObjectMapper) : AbstractJdbcConfiguration() {

    override fun userConverters(): List<Any> = listOf(
        JsonNodeToPgObjectConverter,
        PgObjectToJsonNodeConverter(objectMapper),
    )

    @WritingConverter
    object JsonNodeToPgObjectConverter : Converter<JsonNode, PGobject> {
        override fun convert(source: JsonNode) = PGobject().apply {
            type = "jsonb"
            value = source.toString()
        }
    }

    @ReadingConverter
    class PgObjectToJsonNodeConverter(private val mapper: ObjectMapper) : Converter<PGobject, JsonNode> {
        override fun convert(source: PGobject): JsonNode = mapper.readTree(source.value ?: "null")
    }
}
