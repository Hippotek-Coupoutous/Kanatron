package com.kanatron.app.data

/** The in-flight recording file, shared so startup recovery leaves it alone. */
internal object RecordingActivePath {
    @Volatile var path: String? = null

    /** Registering a recorder and adopting its file must not interleave, or recovery takes a live file. */
    fun <T> exclusive(block: () -> T): T = synchronized(this) { block() }
}
