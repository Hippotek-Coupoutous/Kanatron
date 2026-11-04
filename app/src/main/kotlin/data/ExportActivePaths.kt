package com.ottomusic.sketch.data

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Process-wide paths that a startup orphan sweep must leave alone. */
internal object ExportActivePaths {
    private val paths = ConcurrentHashMap.newKeySet<String>()
    private val guard = Any()

    fun begin(file: File) = synchronized(guard) { paths += file.absolutePath }
    fun finish(file: File) = synchronized(guard) { paths -= file.absolutePath }
    fun snapshot(): Set<String> = synchronized(guard) { paths.toSet() }
    fun createTracked(create: () -> File): File = synchronized(guard) {
        create().also { paths += it.absolutePath }
    }
    fun <T> duringSweep(action: (Set<String>) -> T): T = synchronized(guard) { action(paths.toSet()) }
}