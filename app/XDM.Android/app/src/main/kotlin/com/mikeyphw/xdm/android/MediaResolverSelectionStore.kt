package com.mikeyphw.xdm.android

import android.content.Context
import com.mikeyphw.xdm.android.media.MediaTrackSelection
import com.mikeyphw.xdm.android.media.MediaTrackSelectionCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Small browser-neutral preference store for resolver track choices.
 *
 * Track selections are UX state, not download records, so they intentionally stay outside Room.
 * The selected IDs are opaque local identifiers and no URL, cookie, header, or token is persisted.
 */
class MediaResolverSelectionStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val mutableSelections = MutableStateFlow(load())

    val selections: StateFlow<Map<String, MediaTrackSelection>> = mutableSelections.asStateFlow()

    fun save(captureId: String, selection: MediaTrackSelection) {
        if (captureId.isBlank()) return
        val next = mutableSelections.value.toMutableMap()
        if (selection.selectedIds().isEmpty()) {
            preferences.edit().remove(key(captureId)).apply()
            next.remove(captureId)
        } else {
            preferences.edit().putString(key(captureId), MediaTrackSelectionCodec.encode(selection)).apply()
            next[captureId] = selection
        }
        mutableSelections.value = next.toMap()
    }

    fun remove(captureId: String) {
        if (captureId.isBlank()) return
        preferences.edit().remove(key(captureId)).apply()
        mutableSelections.value = mutableSelections.value - captureId
    }

    fun prune(activeCaptureIds: Set<String>) {
        val staleIds = mutableSelections.value.keys - activeCaptureIds
        if (staleIds.isEmpty()) return
        preferences.edit().also { editor -> staleIds.forEach { editor.remove(key(it)) } }.apply()
        mutableSelections.value = mutableSelections.value.filterKeys(activeCaptureIds::contains)
    }

    private fun load(): Map<String, MediaTrackSelection> = preferences.all.mapNotNull { (storedKey, rawValue) ->
        if (!storedKey.startsWith(KEY_PREFIX)) return@mapNotNull null
        val captureId = storedKey.removePrefix(KEY_PREFIX).takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val encoded = rawValue as? String ?: return@mapNotNull null
        MediaTrackSelectionCodec.decode(encoded)?.let { captureId to it }
    }.toMap()

    private fun key(captureId: String): String = "$KEY_PREFIX$captureId"

    private companion object {
        const val PREFERENCES_NAME = "xdm_media_resolver_selections"
        const val KEY_PREFIX = "selection."
    }
}
