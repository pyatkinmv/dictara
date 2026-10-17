package com.dictara.gateway.config

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * TODO(remove-restore-only): delete this class together with [DictaraProperties.restoreOnly]. It only makes the
 * temporary mode impossible to forget: the gateway says so in its log every time it starts with the mode on.
 */
@Component
class RestoreOnlyModeWarning(private val props: DictaraProperties) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostConstruct
    fun warnIfOn() {
        if (props.restoreOnly) {
            log.warn(
                "RESTORE_ONLY is ON: a file the user already has a transcription of is answered with it whatever options " +
                    "come with it. This is TEMPORARY - once the old audio is back, remove RESTORE_ONLY from .env " +
                    "(and the code, see TODO(remove-restore-only)).",
            )
        }
    }
}
