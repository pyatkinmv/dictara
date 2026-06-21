package com.dictara.gateway.repository

import com.dictara.gateway.entity.JobRunEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface JobRunRepository : JpaRepository<JobRunEntity, UUID>
