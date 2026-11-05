package com.kanatron.app.playback

/** Starts one worker at a time and permits another start after it exits. */
internal class RestartableWorker(private val name: String, private val action: () -> Unit) {
    @Volatile private var thread: Thread? = null

    @Synchronized
    fun start() {
        if (thread?.isAlive == true) return
        val next = Thread({
            try {
                action()
            } finally {
                synchronized(this) {
                    if (thread === Thread.currentThread()) thread = null
                }
            }
        }, name)
        thread = next
        next.start()
    }

    fun join(timeoutMillis: Long) {
        thread?.join(timeoutMillis)
    }
}