package com.kanatron.app.data

import android.util.AtomicFile
import com.kanatron.app.core.Project
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets

class ProjectStore(file: File) {
    private val storage = AtomicFile(file)

    fun save(project: Project) = synchronized(IO_LOCK) {
        val bytes = project.toJson().toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= Project.MAX_JSON_BYTES) { "Project JSON is too large" }
        val stream = storage.startWrite()
        try {
            stream.write(bytes)
            storage.finishWrite(stream)
        } catch (failure: Throwable) {
            storage.failWrite(stream)
            throw failure
        }
    }

    fun load(): Project = synchronized(IO_LOCK) {
        val json = storage.openRead().use { input ->
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= Project.MAX_JSON_BYTES) { "Project JSON is too large" }
                bytes.write(buffer, 0, count)
            }
            String(bytes.toByteArray(), StandardCharsets.UTF_8)
        }
        Project.fromJson(json)
    }

    private companion object {
        /** Android AtomicFile recovery is unsafe while any store instance is writing this process. */
        val IO_LOCK = Any()
    }
}