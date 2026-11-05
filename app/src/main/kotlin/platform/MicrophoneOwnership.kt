package com.kanatron.app.platform

import java.util.concurrent.atomic.AtomicBoolean

/** Process-wide single-input lease shared by the recorder and tuner. */
internal object MicrophoneOwnership {
    private val lock = Any()
    private var active: Lease? = null

    fun tryAcquire(owner: String): Lease? = synchronized(lock) {
        if (active != null) return null
        Lease(owner).also { active = it }
    }

    fun acquire(owner: String): Lease = tryAcquire(owner)
        ?: throw IllegalStateException(busyMessage(owner))

    fun busyMessage(requester: String): String = synchronized(lock) {
        val current = active?.owner ?: return@synchronized "Microphone is busy. Try again."
        when {
            requester == "recording" && current == "tuner" -> "Microphone is in use by the tuner. Close the tuner and try again."
            requester == "tuner" && current == "recording" -> "Microphone is in use by recording. Stop recording before tuning."
            else -> "Microphone is already in use. Stop the active audio session and try again."
        }
    }

    internal class Lease internal constructor(val owner: String) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            synchronized(lock) { if (active === this) active = null }
        }
    }
}
