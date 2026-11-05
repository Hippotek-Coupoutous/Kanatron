package com.ottomusic.sketch.data

/** The in-flight recording file, shared so startup recovery leaves it alone. */
internal object RecordingActivePath {
    @Volatile var path: String? = null
}
