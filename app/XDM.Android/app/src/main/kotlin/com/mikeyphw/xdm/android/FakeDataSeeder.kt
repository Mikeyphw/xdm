package com.mikeyphw.xdm.android

import com.mikeyphw.xdm.android.model.BackendType
import com.mikeyphw.xdm.android.model.Download
import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.model.QueueDefinition
import com.mikeyphw.xdm.android.model.RecoveryClassification
import com.mikeyphw.xdm.android.model.RecoveryRecord
import com.mikeyphw.xdm.android.model.ScheduleRule
import com.mikeyphw.xdm.android.persistence.DownloadRepository
import java.util.UUID

class FakeDataSeeder(private val repository: DownloadRepository) {
    suspend fun seedIfEmpty() {
        val now = System.currentTimeMillis()
        if (repository.countQueues() == 0) {
            repository.saveQueues(
                listOf(
                    QueueDefinition("default", "Default", true, 3, now),
                    QueueDefinition("overnight", "Overnight", true, 2, now + 1),
                ),
            )
            repository.saveSchedules(
                listOf(
                    ScheduleRule("overnight-rule", "overnight", "Overnight Wi‑Fi", true, "{\"unmetered\":true,\"charging\":true}"),
                ),
            )
        }
        if (repository.countDownloads() > 0) return

        repository.saveAll(
            listOf(
                sample("ubuntu.iso", DownloadState.Downloading, BackendType.Native, 2_450_000_000, 5_200_000_000, 18_300_000, now, "default"),
                sample("game-assets.meta4", DownloadState.Queued, BackendType.Aria2, 0, 8_400_000_000, 0, now - 1_000, "overnight"),
                sample("conference-video.mp4", DownloadState.Verifying, BackendType.Native, 1_260_000_000, 1_260_000_000, 0, now - 2_000, "default"),
                sample("android-sdk.zip", DownloadState.Completed, BackendType.Native, 1_850_000_000, 1_850_000_000, 0, now - 3_000, null),
                sample("expired-link.bin", DownloadState.Failed, BackendType.Native, 38_000_000, 440_000_000, 0, now - 4_000, null, "Remote URL expired"),
                sample("recovered.part", DownloadState.RecoveryRequired, BackendType.Native, 620_000_000, 1_100_000_000, 0, now - 5_000, null, "Remote identity requires validation"),
            ),
        )
        repository.saveRecovery(
            listOf(
                RecoveryRecord(
                    id = UUID.randomUUID().toString(),
                    downloadId = null,
                    artifactPath = "/storage/emulated/0/Download/orphan.xdm.part",
                    classification = RecoveryClassification.OrphanedArtifact,
                    reason = "A partial file was found without a matching database record.",
                    createdAtEpochMs = now,
                ),
            ),
        )
    }

    private fun sample(
        fileName: String,
        state: DownloadState,
        backend: BackendType,
        received: Long,
        total: Long,
        speed: Long,
        updated: Long,
        queueId: String?,
        error: String? = null,
    ) = Download(
        id = UUID.randomUUID().toString(),
        fileName = fileName,
        sourceUrl = "https://downloads.example.test/$fileName",
        destinationUri = "content://downloads/$fileName",
        state = state,
        backend = backend,
        bytesReceived = received,
        totalBytes = total,
        speedBytesPerSecond = speed,
        queueId = queueId,
        priority = 0,
        createdAtEpochMs = updated - 60_000,
        updatedAtEpochMs = updated,
        errorMessage = error,
    )

    suspend fun seedQueuesOnly() {
        if (repository.countQueues() > 0) return
        repository.saveQueues(listOf(QueueDefinition("default", "Default", true, 3, System.currentTimeMillis())))
    }
}
