package com.dictara.gateway.repository

import java.util.UUID

/** A transcription shown as a chip on a tag or speaker card: its id (`job_id`, to open it) and the title to show. */
data class LibrarySample(val jobId: UUID, val title: String)
