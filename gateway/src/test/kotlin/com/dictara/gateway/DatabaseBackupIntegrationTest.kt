package com.dictara.gateway

import com.dictara.gateway.job.DatabaseBackupService
import com.dictara.gateway.storage.AudioStorage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.mock.mockito.MockBean

@SpringBootTest(webEnvironment = RANDOM_PORT)
class DatabaseBackupIntegrationTest : AbstractSharedContextIntegrationTest() {

    @Autowired lateinit var databaseBackupService: DatabaseBackupService
    @MockBean lateinit var audioStorage: AudioStorage

    @Test
    fun `backup records completed job run`() {
        databaseBackupService.backup()

        val status = jdbcTemplate.queryForObject(
            "SELECT status FROM job_runs WHERE job_name = 'daily_db_backup' ORDER BY started_at DESC LIMIT 1",
            String::class.java
        )
        assertThat(status).isEqualTo("completed")
    }
}
