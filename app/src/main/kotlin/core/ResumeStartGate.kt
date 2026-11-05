package com.kanatron.app.core

/** Defers a permission-result action until the host is visible again. */
class ResumeStartGate {
    private var pending = false

    fun request(isResumed: Boolean): Boolean {
        if (!isResumed) {
            pending = true
            return false
        }
        pending = false
        return true
    }

    fun onResume(permissionGranted: Boolean): Boolean {
        if (!pending) return false
        pending = false
        return permissionGranted
    }
}