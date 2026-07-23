package com.mikeyphw.xdm.android

import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
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
    Card(modifier.fillMaxWidth().semantics { contentDescription = "Media3 player ${candidate.title}" }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            XdmCardTitle("Media3 player")
            XdmSupportingText(
                if (candidate.needsExternalResolver) {
                    "Adaptive or protected media must be resolved into a safe offline asset before playback."
                } else {
                    "Completed direct media can be reviewed here before opening it from the offline library."
                },
                maxLines = 3,
            )
            if (candidate.needsExternalResolver) {
                XdmMetadataText("Player withheld until resolver prepares a direct local/direct-safe URL.", maxLines = 2)
            } else {
                val context = LocalContext.current
                var playerDiagnostic by remember(candidate.playbackUrl) { mutableStateOf("Media3 player diagnostics: preparing source.") }
                var playerErrorSnapshot by remember(candidate.playbackUrl) { mutableStateOf<MediaPlayerErrorSnapshot?>(null) }
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
                            playerDiagnostic = when (playbackState) {
                                Player.STATE_BUFFERING -> "Media3 player diagnostics: buffering direct media."
                                Player.STATE_READY -> "Media3 player diagnostics: ready for direct playback."
                                Player.STATE_ENDED -> "Media3 player diagnostics: playback ended."
                                Player.STATE_IDLE -> "Media3 player diagnostics: idle; retry prepare if the file changed."
                                else -> "Media3 player diagnostics: state=$playbackState."
                            }
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            playerErrorSnapshot = MediaPlayerErrorSnapshot(
                                errorCodeName = error.errorCodeName,
                                message = error.message,
                                playbackStateLabel = "state=${player.playbackState}",
                                playWhenReady = player.playWhenReady,
                                suppressionReasonLabel = "suppression=${player.playbackSuppressionReason}",
                            )
                            playerDiagnostic = "Media3 player error diagnostics: ${error.errorCodeName} ${error.message.orEmpty().take(120)}"
                        }
                    }
                    player.addListener(listener)
                    onDispose {
                        player.removeListener(listener)
                        player.release()
                    }
                }
                AndroidView(
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                    factory = { viewContext ->
                        PlayerView(viewContext).apply {
                            this.player = player
                            useController = true
                            contentDescription = "Media3 direct media player"
                        }
                    },
                    update = { it.player = player },
                )
                XdmMetadataText(playerDiagnostic, maxLines = 2)
                val playerReport = MediaPlayerDiagnosticsPlanner().report(candidate, playerErrorSnapshot, playbackPositionMs, player.duration.takeIf { it > 0L })
                XdmListCard(compact = true) {
                    XdmMetadataText("Player 2.0 diagnostics")
                    XdmSupportingText(playerReport.summary, maxLines = 3)
                    XdmMetadataText("Track availability: ${playerReport.tracks.joinToString { it.summary }}", maxLines = 2)
                    playerReport.subtitleRows.takeIf { it.isNotEmpty() }?.let { rows -> XdmMetadataText("Subtitle availability: ${rows.joinToString { it.summary }}", maxLines = 2) }
                    XdmMetadataText("Playback position: ${playerReport.positionMemory.summary}", maxLines = 2)
                    XdmMetadataText(if (playerReport.protectedDiagnosticOnly) "Protected media diagnostics only; XDM does not bypass DRM." else "Retry prepare is available for source/player failures after review.", maxLines = 2)
                }
                TextButton(onClick = {
                    playerDiagnostic = "Media3 player diagnostics: retrying prepare."
                    playerErrorSnapshot = null
                    player.prepare()
                }) { Text("Retry player prepare") }
                Text(
                    text = candidate.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                )
            }
        }
    }
}
