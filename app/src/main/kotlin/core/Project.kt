package com.kanatron.app.core

import java.security.MessageDigest

/** Charset and length shared by project, track, clip and take ids. */
const val PROJECT_ID_PATTERN = "[A-Za-z0-9-]{1,80}"
val PROJECT_ID = Regex(PROJECT_ID_PATTERN)

/** Lowercase SHA-256 hex, shared by file fingerprints and retention metadata. */
internal fun sha256Hex(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/** Immutable identity and format metadata for a stored source WAV. */
data class ProjectAudioSource(
    val wav: String,
    val sampleRate: Int,
    val channels: Int,
    val totalFrames: Long
) {
    init {
        require(wav.isNotBlank())
        require(sampleRate in SUPPORTED_SAMPLE_RATES)
        require(channels in 1..2)
        require(totalFrames > 0L)
    }
}

/** A source slice placed on the project timeline, measured in the owning project's sample rate. */
data class ProjectClip(
    val id: String,
    val source: ProjectAudioSource,
    val positionFrames: Long,
    val trim: TrimRange,
    val rateCorrectionPpm: Int = 0
) {
    init {
        require(id.matches(PROJECT_ID))
        require(positionFrames >= 0L)
        trim.requireValidFor(source.totalFrames)
        require(rateCorrectionPpm in -MAX_RATE_CORRECTION_PPM..MAX_RATE_CORRECTION_PPM) {
            "Rate correction must be within ±$MAX_RATE_CORRECTION_PPM ppm."
        }
    }

    companion object { const val MAX_RATE_CORRECTION_PPM = 2_000 }
}

data class ProjectTrack(
    val id: String,
    val name: String,
    val clips: List<ProjectClip>
) {
    init {
        require(id.matches(PROJECT_ID))
        require(name.isNotBlank())
        require(clips.isNotEmpty())
    }
}

/** The first track's first clip; every project starts from one recording. */
val Project.firstClip: ProjectClip get() = tracks.first().clips.first()

/** A saved recording this version no longer plays the way it was made. */
class UnsupportedProjectException(message: String) : IllegalArgumentException(message)

private fun requireValidTracks(tracks: List<ProjectTrack>, channels: Int, sampleRate: Int) {
    require(tracks.size == 1) { "A project has exactly one track." }
    require(tracks.map(ProjectTrack::id).distinct().size == tracks.size) { "Track ids must be unique." }
    val clips = tracks.flatMap(ProjectTrack::clips)
    require(clips.map(ProjectClip::id).distinct().size == clips.size) { "Clip ids must be unique." }
    require(clips.size <= Project.MAX_CLIPS) { "A project supports up to ${Project.MAX_CLIPS} clips." }
    require(clips.maxOf { it.source.channels } == channels) { "Project channels do not match its sources." }
    require(clips.any { it.source.sampleRate == sampleRate }) { "The project sample rate must match at least one source rate." }
    require(MultitrackRenderer.frameCount(tracks, sampleRate) > 0L) { "A project must contain audio." }
}

data class Project(
    val id: String,
    val name: String,
    val sampleRate: Int,
    val channels: Int,
    val createdAtEpochMillis: Long,
    val silent: Boolean = false,
    /** A recovered, unsaved take: stored on disk but hidden from the Library until explicitly saved. */
    val draft: Boolean = false,
    val tracks: List<ProjectTrack>
) {
    init {
        require(id.matches(PROJECT_ID))
        require(name.isNotBlank())
        require(sampleRate in SUPPORTED_SAMPLE_RATES)
        require(channels in 1..2)
        requireValidTracks(tracks, channels, sampleRate)
    }

    /** The exact project length implied by its clips; clip trims do the trimming. */
    val totalFrames: Long get() = MultitrackRenderer.frameCount(tracks, sampleRate)

    fun toJson(): String = buildString {
        append('{')
        append("\"schemaVersion\":4,")
        append("\"id\":").append(quote(id)).append(',')
        append("\"name\":").append(quote(name)).append(',')
        append("\"sampleRate\":").append(sampleRate).append(',')
        append("\"channels\":").append(channels).append(',')
        append("\"silent\":").append(silent).append(',')
        append("\"draft\":").append(draft).append(',')
        append("\"tracks\":").append(tracks.tracksToJson())
        append(",\"createdAtEpochMillis\":").append(createdAtEpochMillis)
        append('}')
    }

    companion object {
        const val MAX_JSON_BYTES = 64 * 1024
        /** Edit's timeline is one clip per kept piece, so this bounds cuts, not tracks. */
        const val MAX_CLIPS = 256

        fun fromJson(json: String): Project {
            require(json.toByteArray(Charsets.UTF_8).size <= MAX_JSON_BYTES) { "Project JSON is too large" }
            val root = JsonReader(json).read() as? Map<*, *> ?: throw IllegalArgumentException("Project JSON must be an object")
            val version = root.long("schemaVersion")
            require(version == 3L || version == 4L) { "Unsupported project schema version: $version" }
            val rawTracks = root["tracks"].asArray("tracks").map { it.asObject("track") }
            if (rawTracks.size != 1) throw UnsupportedProjectException("Multi-track recordings are no longer supported.")
            // Version 3 also stored gains, mutes and solo; only their defaults still sound the same.
            val mixed = rawTracks.any { track ->
                track["gainPermille"] != 1_000L || track["muted"] == true || track["solo"] == true ||
                    track["clips"].asArray("clips").any { val clip = it.asObject("clip"); clip["gainPermille"] != 1_000L || clip["muted"] == true }
            }
            if (version == 3L && mixed) throw UnsupportedProjectException("Recordings with mixer settings are no longer supported.")
            val tracks = rawTracks.map { it.asTrack() }
            return Project(
                id = root.string("id"),
                name = root.string("name"),
                sampleRate = root.long("sampleRate").toIntExact("sampleRate"),
                channels = root.long("channels").toIntExact("channels"),
                createdAtEpochMillis = root.long("createdAtEpochMillis"),
                silent = if (root.containsKey("silent")) root.boolean("silent") else false,
                draft = if (root.containsKey("draft")) root.boolean("draft") else false,
                tracks = tracks
            )
        }

        private fun quote(value: String): String = buildString {
            append('"')
            for (character in value) {
                when (character) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (character < ' ') append("\\u%04x".format(character.code)) else append(character)
                }
            }
            append('"')
        }

        private fun Map<*, *>.string(key: String): String =
            this[key] as? String ?: throw IllegalArgumentException("Missing or invalid '$key'")

        private fun Map<*, *>.long(key: String): Long =
            (this[key] as? Long) ?: throw IllegalArgumentException("Missing or invalid '$key'")

        private fun Map<*, *>.boolean(key: String): Boolean =
            this[key] as? Boolean ?: throw IllegalArgumentException("Missing or invalid '$key'")

        private fun Long.toIntExact(field: String): Int {
            if (this !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                throw IllegalArgumentException("'$field' is outside the integer range")
            }
            return toInt()
        }

        private fun Any?.asObject(field: String): Map<*, *> =
            this as? Map<*, *> ?: throw IllegalArgumentException("Missing or invalid '$field'")

        private fun Any?.asArray(field: String): List<*> =
            this as? List<*> ?: throw IllegalArgumentException("Missing or invalid '$field'")

        private fun Map<*, *>.toTrimRange(): TrimRange = TrimRange(long("startFrame"), long("endFrame"))

        private fun Any?.asTrack(): ProjectTrack {
            val track = asObject("track")
            val clips = track["clips"].asArray("clips").map { raw ->
                val clip = raw.asObject("clip")
                val source = clip["source"].asObject("clip source")
                ProjectClip(
                    id = clip.string("id"),
                    source = ProjectAudioSource(
                        wav = source.string("wav"),
                        sampleRate = source.long("sampleRate").toIntExact("source.sampleRate"),
                        channels = source.long("channels").toIntExact("source.channels"),
                        totalFrames = source.long("totalFrames")
                    ),
                    positionFrames = clip.long("positionFrames"),
                    trim = clip["trim"].asObject("clip trim").toTrimRange(),
                    rateCorrectionPpm = if (clip.containsKey("rateCorrectionPpm")) {
                        clip.long("rateCorrectionPpm").toIntExact("clip.rateCorrectionPpm")
                    } else 0
                )
            }
            return ProjectTrack(
                id = track.string("id"),
                name = track.string("name"),
                clips = clips
            )
        }

        private fun TrimRange.toJsonObject(): String =
            "{\"startFrame\":$startFrame,\"endFrame\":$endFrame}"

        private fun List<ProjectTrack>.tracksToJson(): String = joinToString(prefix = "[", postfix = "]") { track ->
            buildString {
                append("{\"id\":").append(quote(track.id))
                append(",\"name\":").append(quote(track.name))
                append(",\"clips\":[")
                track.clips.forEachIndexed { index, clip ->
                    if (index > 0) append(',')
                    append("{\"id\":").append(quote(clip.id))
                    append(",\"source\":{\"wav\":").append(quote(clip.source.wav))
                    append(",\"sampleRate\":").append(clip.source.sampleRate)
                    append(",\"channels\":").append(clip.source.channels)
                    append(",\"totalFrames\":").append(clip.source.totalFrames).append('}')
                    append(",\"positionFrames\":").append(clip.positionFrames)
                    append(",\"trim\":").append(clip.trim.toJsonObject())
                    append(",\"rateCorrectionPpm\":").append(clip.rateCorrectionPpm).append('}')
                }
                append("]}")
            }
        }

    }
}

private class JsonReader(private val text: String) {
    private var position = 0
    private var depth = 0

    fun read(): Any? {
        val result = value()
        whitespace()
        require(position == text.length) { "Unexpected trailing JSON at $position" }
        return result
    }

    private fun value(): Any? {
        whitespace()
        require(position < text.length) { "Unexpected end of JSON" }
        return when (text[position]) {
            '{' -> objectValue()
            '[' -> arrayValue()
            '"' -> stringValue()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            '-', in '0'..'9' -> numberValue()
            else -> throw IllegalArgumentException("Invalid JSON value at $position")
        }
    }

    private fun objectValue(): Map<String, Any?> {
        enterContainer()
        position++
        whitespace()
        val result = linkedMapOf<String, Any?>()
        if (consume('}')) return result.also { depth-- }
        while (true) {
            whitespace()
            require(position < text.length && text[position] == '"') { "Expected JSON object key at $position" }
            val key = stringValue()
            whitespace()
            require(consume(':')) { "Expected ':' at $position" }
            require(!result.containsKey(key)) { "Duplicate JSON key '$key'" }
            result[key] = value()
            whitespace()
            if (consume('}')) return result.also { depth-- }
            require(consume(',')) { "Expected ',' at $position" }
        }
    }

    private fun arrayValue(): List<Any?> {
        enterContainer()
        position++
        whitespace()
        val result = mutableListOf<Any?>()
        if (consume(']')) return result.also { depth-- }
        while (true) {
            result += value()
            whitespace()
            if (consume(']')) return result.also { depth-- }
            require(consume(',')) { "Expected ',' at $position" }
        }
    }

    private fun stringValue(): String {
        require(consume('"'))
        val result = StringBuilder()
        while (position < text.length) {
            val character = text[position++]
            when {
                character == '"' -> return result.toString()
                character == '\\' -> {
                    require(position < text.length) { "Incomplete JSON escape" }
                    when (val escaped = text[position++]) {
                        '"', '\\', '/' -> result.append(escaped)
                        'b' -> result.append('\b')
                        'f' -> result.append('\u000C')
                        'n' -> result.append('\n')
                        'r' -> result.append('\r')
                        't' -> result.append('\t')
                        'u' -> {
                            require(position + 4 <= text.length) { "Incomplete JSON unicode escape" }
                            val code = text.substring(position, position + 4).toIntOrNull(16)
                                ?: throw IllegalArgumentException("Invalid JSON unicode escape")
                            result.append(code.toChar())
                            position += 4
                        }
                        else -> throw IllegalArgumentException("Invalid JSON escape: $escaped")
                    }
                }
                character < ' ' -> throw IllegalArgumentException("Unescaped control character in JSON string")
                else -> result.append(character)
            }
        }
        throw IllegalArgumentException("Unterminated JSON string")
    }

    private fun numberValue(): Long {
        val start = position
        if (text[position] == '-') position++
        require(position < text.length) { "Invalid JSON number" }
        if (text[position] == '0') {
            position++
        } else {
            require(text[position] in '1'..'9') { "Invalid JSON number" }
            while (position < text.length && text[position] in '0'..'9') position++
        }
        require(position == text.length || text[position] !in ".eE") { "Project values must use integer JSON numbers" }
        return text.substring(start, position).toLongOrNull() ?: throw IllegalArgumentException("JSON integer is out of range")
    }

    private fun <T> literal(expected: String, result: T): T {
        require(text.startsWith(expected, position)) { "Invalid JSON literal at $position" }
        position += expected.length
        return result
    }

    private fun whitespace() {
        while (position < text.length && text[position] in charArrayOf(' ', '\n', '\r', '\t')) position++
    }

    private fun consume(character: Char): Boolean {
        if (position < text.length && text[position] == character) {
            position++
            return true
        }
        return false
    }

    private fun enterContainer() {
        depth++
        require(depth <= MAX_DEPTH) { "Project JSON nesting is too deep" }
    }

    private companion object { const val MAX_DEPTH = 32 }
}