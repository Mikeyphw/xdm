package com.mikeyphw.xdm.android

import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.mikeyphw.xdm.android.media.MediaPlaybackCandidate
import com.mikeyphw.xdm.android.media.MediaPlayerDiagnosticsPlanner
import com.mikeyphw.xdm.android.media.MediaPlayerErrorSnapshot

@OptIn(UnstableApi::class)
@Composable
fun Media3DirectPlayerCard(candidate: MediaPlaybackCandidate, modifier: Modifier = Modifier) {
    val windowProfile = LocalXdmWindowProfile.current
    XdmFlatCard(modifier.fillMaxWidth().xdmPane("Media player pane").semantics { contentDescription = "Player for ${candidate.title}" }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            XdmCardTitle(candidate.title, maxLines = 2)
            if (candidate.needsExternalResolver) {
                XdmNoticeRow(
                    text = "This item must finish resolving before it can be played safely.",
                    tone = XdmStatusTone.Warning,
                )
                return@Column
            }

            val context = LocalContext.current
            var playerError by remember(candidate.playbackUrl) { mutableStateOf<MediaPlayerErrorSnapshot?>(null) }
            var playbackPositionMs by remember(candidate.playbackUrl) { mutableStateOf(0L) }
            val player = remember(candidate.playbackUrl) {
                ExoPlayer.Builder(context).build().apply {
                    setMediaItem(MediaItem.fromUri(Uri.parse(candidate.playbackUrl)))
                    playWhenReady = false
                    prepare()
                }
            }
            DisposableEffect(player) {
                val listener = object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        playbackPositionMs = player.currentPosition.coerceAtLeast(0L)
                        if (playbackState == Player.STATE_READY) playerError = null
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        playerError = MediaPlayerErrorSnapshot(
                            errorCodeName = error.errorCodeName,
                            errorCode = error.errorCode,
                            causeClassName = error.cause?.javaClass?.name,
                            message = error.message,
                            playbackStateLabel = "state=${player.playbackState}",
                            playWhenReady = player.playWhenReady,
                            suppressionReasonLabel = "suppression=${player.playbackSuppressionReason}",
                        )
                    }
                }
                player.addListener(listener)
                onDispose {
                    player.removeListener(listener)
                    player.release()
                }
            }

            AndroidView(
                modifier = Modifier.fillMaxWidth().height(windowProfile.playerHeight).heightIn(min = 160.dp).xdmScreen(XdmScreenTags.Player, "Media player").semantics { isTraversalGroup = true },
                factory = { viewContext ->
                    PlayerView(viewContext).apply {
                        this.player = player
                        useController = true
                        contentDescription = "Media player controls for ${candidate.title}"
                        controllerShowTimeoutMs = 0
                    }
                },
                update = { it.player = player },
            )

            playerError?.let { error ->
                XdmNoticeRow(
                    text = "This file could not be played. It may have moved, be incomplete, or use an unsupported format.",
                    tone = XdmStatusTone.Error,
                    actionLabel = "Retry",
                    onAction = {
                        playerError = null
                        player.prepare()
                    },
                )
                val supportReport = remember(error, playbackPositionMs, player.duration) {
                    MediaPlayerDiagnosticsPlanner().report(
                        candidate = candidate,
                        error = error,
                        positionMs = playbackPositionMs,
                        durationMs = player.duration.takeIf { it > 0L },
                    )
                }
                XdmTechnicalDetails(label = "Support details") {
                    XdmMetadataText(supportReport.summary, maxLines = 3)
                    XdmMetadataText("Playback position: ${supportReport.positionMemory.summary}", maxLines = 2)
                    XdmMetadataText("Track availability: ${supportReport.tracks.joinToString { it.summary }}", maxLines = 3)
                }
            }

            TextButton(onClick = { player.seekTo(0L) }, modifier = Modifier.xdmMinimumTouchTarget()) { Text("Restart from beginning") }
        }
    }
}
