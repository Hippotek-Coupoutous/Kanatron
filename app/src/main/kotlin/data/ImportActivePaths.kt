package com.ottomusic.sketch.data

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Tracks every in-flight import across Activity recreation until its output is committed or discarded. */
internal object ImportActivePaths {
    private val decode = ConcurrentHashMap.newKeySet<String>()
    private val staging = ConcurrentHashMap.newKeySet<String>()

    fun beginOutput(file: File) { decode += file.absolutePath }
    fun beginStaging(file: File) { staging += file.absolutePath }
    fun finishStaging(file: File) { staging -= file.absolutePath }
    fun finishOutput(file: File) { decode -= file.absolutePath }
    fun importerFinished(output: File, stagedFile: File?, completed: Boolean) {
        stagedFile?.let(::finishStaging)
        if (!completed) finishOutput(output)
    }
    fun activeDecodePaths(): Set<String> = decode.toSet()
    fun activeStagingPaths(): Set<String> = staging.toSet()
}