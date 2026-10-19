package com.dictara.gateway.service

/**
 * Which representation of a library list `GET /tags` and `GET /speakers` answer with (`view=`). The default is the
 * whole thing, so that a client that does not know the parameter gets what it always got; `brief` is only what a
 * menu or a filter needs (no counts, times or sample titles, which are what makes the whole list slow).
 */
enum class LibraryView(val param: String) {
    FULL("full"),
    BRIEF("brief");

    companion object {
        /** The view named by [raw] (no parameter means [FULL]); null for a name that is not one. */
        fun parse(raw: String?): LibraryView? = if (raw == null) FULL else entries.firstOrNull { it.param == raw }

        val names: List<String> get() = entries.map { it.param }
    }
}
