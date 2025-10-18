package com.devrhythm


import com.intellij.util.messages.Topic

interface DevRhythmInputEvents {
    companion object {
        @JvmField
        val TOPIC: Topic<DevRhythmInputEvents> =
            Topic.create("DevRhythmInputEvents", DevRhythmInputEvents::class.java)
    }

    /** Keystroke or mouse click time (epoch millis). */
    fun onInput(epochMillis: Long)
}
