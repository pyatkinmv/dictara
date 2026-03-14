package com.dictara.gateway.config

import com.dictara.gateway.adapter.GeminiSummarizer
import com.dictara.gateway.adapter.NoopSummarizer
import com.dictara.gateway.port.SummarizerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class SummarizerConfig(private val props: DictaraProperties) {

    @Bean
    fun summarizer(): SummarizerPort {
        val geminiProps = props.summarizer.gemini
        return when (props.summarizer.provider.lowercase()) {
            "gemini" -> if (geminiProps.apiKey.isNotBlank())
                GeminiSummarizer(geminiProps.apiKey, geminiProps.model)
            else
                NoopSummarizer()
            else -> NoopSummarizer()
        }
    }
}
