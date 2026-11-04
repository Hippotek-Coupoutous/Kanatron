package com.ottomusic.sketch.core

/** Runs every finalization step and preserves the first storage or release failure. */
object TakeFinalization {
    fun finish(
        initialError: String?,
        syncData: () -> Unit,
        closeData: () -> Unit,
        writeHeader: () -> Unit,
        releaseRecorder: () -> Unit
    ): String? {
        var error = initialError
        fun attempt(message: String, action: () -> Unit) {
            try {
                action()
            } catch (failure: Throwable) {
                error = error ?: "$message: ${failure.message}"
            }
        }
        attempt("Could not sync recording data", syncData)
        attempt("Could not close recording data", closeData)
        attempt("Could not finalize recording", writeHeader)
        attempt("Could not release microphone", releaseRecorder)
        return error
    }
}