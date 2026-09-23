package com.dictara.gateway.service

/** How `GET /transcriptions` orders its list (`sort=`). Ties are always broken by creation time and id. */
enum class ListSort(val param: String) {
    /** Newest first. The default. */
    NEWEST("newest"),
    OLDEST("oldest"),

    /** Longest audio first; entries whose length is not known yet come last. */
    LONGEST("longest"),

    /** By the title shown in the list, without regard to case. */
    NAME("name");

    companion object {
        fun parse(value: String): ListSort? = entries.firstOrNull { it.param == value }
    }
}

/** The status names a client may filter by (`status=`), and the statuses the gateway stores for each. */
object ListStatuses {
    /** `processing` covers the summarizing step too, which the list shows as part of processing. */
    val stored: Map<String, List<String>> = mapOf(
        "pending" to listOf("pending"),
        "processing" to listOf("processing", "summarizing"),
        "done" to listOf("done"),
        "failed" to listOf("failed"),
    )
}

/**
 * What `GET /transcriptions` was asked to narrow the list down to.
 *
 * @property search text to find in the title shown in the list (the transcript's title, or the file name
 *   when it has none), ignoring case; null or blank means no search
 * @property statuses any of these (client-side names, see [ListStatuses]); empty means all
 * @property tags any of these tag names; empty means all
 * @property speakers any of these library speaker names actually speak in the transcript — a phrase's own
 *   mark, else its voice's confirmed speaker (never an unconfirmed "Speaker N" voice, which matches no name);
 *   empty means all
 */
data class TranscriptionListFilter(
    val search: String? = null,
    val statuses: Set<String> = emptySet(),
    val tags: Set<String> = emptySet(),
    val speakers: Set<String> = emptySet(),
    val sort: ListSort = ListSort.NEWEST,
)
