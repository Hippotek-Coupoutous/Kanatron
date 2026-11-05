package com.ottomusic.sketch.core

/** Charset and length shared by project, track, clip and take ids. */
const val PROJECT_ID_PATTERN = "[A-Za-z0-9-]{1,80}"
val PROJECT_ID = Regex(PROJECT_ID_PATTERN)

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
    val gainPermille: Int = 1_000,
    val muted: Boolean = false,
    val rateCorrectionPpm: Int = 0
) {
    init {
        require(id.matches(PROJECT_ID))
        require(positionFrames >= 0L)
        trim.requireValidFor(source.totalFrames)
        require(gainPermille in 0..2_000)
        require(rateCorrectionPpm in -MAX_RATE_CORRECTION_PPM..MAX_RATE_CORRECTION_PPM) {
            "Rate correction must be within ±$MAX_RATE_CORRECTION_PPM ppm."
        }
    }

    companion object { const val MAX_RATE_CORRECTION_PPM = 2_000 }
}

data class ProjectTrack(
    val id: String,
    val name: String,
    val clips: List<ProjectClip>,
    val gainPermille: Int = 1_000,
    val muted: Boolean = false,
    val solo: Boolean = false
) {
    init {
        require(id.matches(PROJECT_ID))
        require(name.isNotBlank())
        require(clips.isNotEmpty())
        require(gainPermille in 0..2_000)
    }
}

data class ProjectArrangementSnapshot(
    val trim: TrimRange,
    val tracks: List<ProjectTrack>,
    val totalFrames: Long,
    val trimUndoHistory: List<TrimRange> = emptyList()
)

private fun defaultProjectTracks(
    name: String,
    sourceWav: String,
    sampleRate: Int,
    channels: Int,
    totalFrames: Long
): List<ProjectTrack> = listOf(
    ProjectTrack(
        id = "track-main",
        name = name,
        clips = listOf(
            ProjectClip(
                id = "clip-main",
                source = ProjectAudioSource(sourceWav, sampleRate, channels, totalFrames),
                positionFrames = 0L,
                trim = TrimRange(0L, totalFrames)
            )
        )
    )
)

data class Project(
    val id: String,
    val name: String,
    val sourceWav: String,
    val sampleRate: Int,
    val channels: Int,
    val totalFrames: Long,
    val trim: TrimRange,
    val createdAtEpochMillis: Long,
    val silent: Boolean = false,
    val undoHistory: List<TrimRange> = emptyList(),
    val tracks: List<ProjectTrack> = defaultProjectTracks(name, sourceWav, sampleRate, channels, totalFrames),
    val isArrangement: Boolean = false,
    val arrangementUndoHistory: List<ProjectArrangementSnapshot> = emptyList(),
    internal val migrationPending: Boolean = false
) {
    init {
        require(id.matches(PROJECT_ID))
        require(name.isNotBlank())
        require(if (isArrangement) sourceWav.isEmpty() else sourceWav.isNotBlank())
        require(sampleRate in SUPPORTED_SAMPLE_RATES)
        require(channels in 1..2)
        require(totalFrames > 0)
        trim.requireValidFor(totalFrames)
        require(undoHistory.size <= UndoPolicy.MAX_UNDO) { "Undo history exceeds its limit" }
        undoHistory.forEach { it.requireValidFor(totalFrames) }
        require(tracks.isNotEmpty())
        require(tracks.size <= MAX_TRACKS) { "An arrangement supports up to $MAX_TRACKS tracks." }
        require(tracks.map(ProjectTrack::id).distinct().size == tracks.size) { "Track ids must be unique." }
        require(tracks.flatMap(ProjectTrack::clips).map(ProjectClip::id).distinct().size == tracks.sumOf { it.clips.size }) { "Clip ids must be unique." }
        require(tracks.sumOf { it.clips.size } <= MAX_CLIPS) { "An arrangement supports up to $MAX_CLIPS clips." }
        require(arrangementUndoHistory.size <= UndoPolicy.MAX_UNDO) { "Arrangement undo history exceeds its limit" }
        arrangementUndoHistory.forEach { snapshot ->
            snapshot.trim.requireValidFor(snapshot.totalFrames)
            require(snapshot.trimUndoHistory.size <= UndoPolicy.MAX_UNDO)
            snapshot.trimUndoHistory.forEach { it.requireValidFor(snapshot.totalFrames) }
            require(MultitrackRenderer.frameCount(snapshot.tracks, sampleRate) == snapshot.totalFrames)
            require(snapshot.tracks.isNotEmpty() && snapshot.tracks.size <= MAX_TRACKS)
            require(snapshot.tracks.sumOf { it.clips.size } <= MAX_CLIPS)
        }
        if (isArrangement) {
            require(MultitrackRenderer.frameCount(tracks, sampleRate) == totalFrames) {
                "Arrangement length does not match its clips."
            }
            require(tracks.flatMap(ProjectTrack::clips).maxOf { it.source.channels } == channels) {
                "Arrangement channels do not match its sources."
            }
            require(tracks.flatMap(ProjectTrack::clips).any { it.source.sampleRate == sampleRate }) {
                "The project sample rate must match at least one source rate."
            }
        }
    }

    fun toJson(): String = buildString {
        append('{')
        append("\"schemaVersion\":2,")
        append("\"id\":").append(quote(id)).append(',')
        append("\"name\":").append(quote(name)).append(',')
        append("\"sourceWav\":").append(quote(sourceWav)).append(',')
        append("\"sampleRate\":").append(sampleRate).append(',')
        append("\"channels\":").append(channels).append(',')
        append("\"totalFrames\":").append(totalFrames).append(',')
        append("\"trim\":").append(trim.toJsonObject()).append(',')
        append("\"silent\":").append(silent).append(',')
        append("\"undoHistory\":").append(undoHistory.rangesToJson()).append(',')
        append("\"isArrangement\":").append(isArrangement).append(',')
        append("\"tracks\":").append(tracks.tracksToJson()).append(',')
        append("\"arrangementUndoHistory\":[")
        arrangementUndoHistory.forEachIndexed { index, snapshot ->
            if (index > 0) append(',')
            append("{\"trim\":").append(snapshot.trim.toJsonObject())
            append(",\"tracks\":").append(snapshot.tracks.tracksToJson())
            append(",\"totalFrames\":").append(snapshot.totalFrames)
            append(",\"trimUndoHistory\":").append(snapshot.trimUndoHistory.rangesToJson()).append('}')
        }
        append("],\"createdAtEpochMillis\":").append(createdAtEpochMillis)
        append('}')
    }

    companion object {
        const val MAX_JSON_CHARS = 64 * 1024
        const val MAX_TRACKS = 4
        const val MAX_CLIPS = 16

        fun fromJson(json: String): Project {
            require(json.length <= MAX_JSON_CHARS) { "Project JSON is too large" }
            val root = JsonReader(json).read() as? Map<*, *> ?: throw IllegalArgumentException("Project JSON must be an object")
            val version = root.long("schemaVersion")
            require(version in 1L..2L) { "Unsupported project schema version: $version" }
            val trim = root["trim"].asObject("trim").toTrimRange()
            val undoHistory = root["undoHistory"].asTrimHistory("undo history")
            val id = root.string("id")
            val name = root.string("name")
            val sourceWav = root.string("sourceWav")
            val sampleRate = root.long("sampleRate").toIntExact("sampleRate")
            val channels = root.long("channels").toIntExact("channels")
            val totalFrames = root.long("totalFrames")
            val arrangement = version >= 2L && root.boolean("isArrangement")
            val tracks = if (version == 1L) {
                defaultProjectTracks(name, sourceWav, sampleRate, channels, totalFrames)
            } else {
                root["tracks"].asArray("tracks").map { it.asTrack() }
            }
            val history = if (version == 1L) emptyList() else root["arrangementUndoHistory"].asArray("arrangement undo history").map {
                val entry = it.asObject("arrangement undo entry")
                ProjectArrangementSnapshot(
                    trim = entry["trim"].asObject("arrangement undo trim").toTrimRange(),
                    tracks = entry["tracks"].asArray("arrangement undo tracks").map { track -> track.asTrack() },
                    totalFrames = entry.long("totalFrames"),
                    trimUndoHistory = entry["trimUndoHistory"].asTrimHistory("arrangement trim undo history")
                )
            }
            return Project(
                id = id,
                name = name,
                sourceWav = sourceWav,
                sampleRate = sampleRate,
                channels = channels,
                totalFrames = totalFrames,
                trim = trim,
                createdAtEpochMillis = root.long("createdAtEpochMillis"),
                silent = if (root.containsKey("silent")) root.boolean("silent") else false,
                undoHistory = undoHistory,
                tracks = tracks,
                isArrangement = arrangement,
                arrangementUndoHistory = history,
                migrationPending = version == 1L
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

        private fun Any?.asTrimHistory(field: String): List<TrimRange> = when (this) {
            null -> emptyList()
            is List<*> -> map { it.asObject(field).toTrimRange() }
            else -> throw IllegalArgumentException("Invalid $field")
        }

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
                    gainPermille = clip.long("gainPermille").toIntExact("clip.gainPermille"),
                    muted = clip.boolean("muted"),
                    rateCorrectionPpm = if (clip.containsKey("rateCorrectionPpm")) {
                        clip.long("rateCorrectionPpm").toIntExact("clip.rateCorrectionPpm")
                    } else 0
                )
            }
            return ProjectTrack(
                id = track.string("id"),
                name = track.string("name"),
                clips = clips,
                gainPermille = track.long("gainPermille").toIntExact("track.gainPermille"),
                muted = track.boolean("muted"),
                solo = track.boolean("solo")
            )
        }

        private fun TrimRange.toJsonObject(): String =
            "{\"startFrame\":$startFrame,\"endFrame\":$endFrame}"

        private fun List<TrimRange>.rangesToJson(): String = joinToString(prefix = "[", postfix = "]") { it.toJsonObject() }

        private fun List<ProjectTrack>.tracksToJson(): String = joinToString(prefix = "[", postfix = "]") { track ->
            buildString {
                append("{\"id\":").append(quote(track.id))
                append(",\"name\":").append(quote(track.name))
                append(",\"gainPermille\":").append(track.gainPermille)
                append(",\"muted\":").append(track.muted)
                append(",\"solo\":").append(track.solo)
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
                    append(",\"gainPermille\":").append(clip.gainPermille)
                    append(",\"rateCorrectionPpm\":").append(clip.rateCorrectionPpm)
                    append(",\"muted\":").append(clip.muted).append('}')
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