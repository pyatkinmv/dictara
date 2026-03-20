package com.dictara.gateway.entity

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity @Table(name = "submissions")
class SubmissionEntity(
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,
    @ManyToOne(fetch = FetchType.EAGER) @JoinColumn(name = "user_id", nullable = false)
    val user: UserEntity,
    @ManyToOne(fetch = FetchType.EAGER) @JoinColumn(name = "audio_id", nullable = false)
    val audio: AudioMetaEntity,
    @Column(nullable = false) val model: String = "fast",
    @Column(nullable = false) val language: String = "auto",
    @Column(nullable = false) val diarize: Boolean = false,
    @Column(name = "num_speakers") val numSpeakers: Int? = null,
    @Column(name = "summary_mode", nullable = false) val summaryMode: String = "off",
    @Column(nullable = false) val source: String = "web",
    @Column(nullable = false) var status: String = "pending",
    @Column(name = "created_at", nullable = false, updatable = false) val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant = Instant.now(),
)
