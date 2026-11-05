package com.kanatron.app.core

data class TrimRange(val startFrame: Long, val endFrame: Long) {
    init {
        require(startFrame >= 0) { "Start frame must be non-negative" }
        require(endFrame > startFrame) { "Trim range must contain at least one frame" }
    }

    val frameCount: Long get() = endFrame - startFrame

    fun requireValidFor(totalFrames: Long) {
        require(totalFrames > 0 && endFrame <= totalFrames) { "Trim range must fit inside the source" }
    }
}