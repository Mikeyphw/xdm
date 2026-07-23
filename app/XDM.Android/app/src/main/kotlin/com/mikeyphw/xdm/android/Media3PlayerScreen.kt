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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.mikeyphw.xdm.android.media.MediaPlaybackCandidate

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
                val player = remember(candidate.playbackUrl) {
                    ExoPlayer.Builder(context).build().apply {
                        setMediaItem(MediaItem.fromUri(Uri.parse(candidate.playbackUrl)))
                        playWhenReady = false
                        prepare()
                    }
                }
                DisposableEffect(player) {
                    onDispose { player.release() }
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
                Text(
                    text = candidate.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                )
            }
        }
    }
}
