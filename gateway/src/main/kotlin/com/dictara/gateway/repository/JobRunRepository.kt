package com.dictara.gateway.repository

import com.dictara.gateway.entity.JobRunEntity
import org.springframework.data.repository.CrudRepository
import java.util.UUID

interface JobRunRepository : CrudRepository<JobRunEntity, UUID>
