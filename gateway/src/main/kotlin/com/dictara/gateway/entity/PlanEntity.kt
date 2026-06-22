package com.dictara.gateway.entity

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table

@Table("plans")
class PlanEntity(
    @Id val name: String,
    val displayName: String,
    val constraints: JsonNode,
)
