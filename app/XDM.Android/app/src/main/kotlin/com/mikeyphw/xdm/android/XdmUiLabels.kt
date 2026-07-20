package com.mikeyphw.xdm.android

import com.mikeyphw.xdm.android.model.BackendBatteryImpact
import com.mikeyphw.xdm.android.model.BackendDiagnosticDetail
import com.mikeyphw.xdm.android.model.BackendMigrationStage
import com.mikeyphw.xdm.android.model.BackendType
import com.mikeyphw.xdm.android.model.ChecksumAlgorithm
import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.model.FilenameConflictPolicy
import com.mikeyphw.xdm.android.model.MediaCaptureStatus
import com.mikeyphw.xdm.android.model.MediaResolutionStatus
import com.mikeyphw.xdm.android.model.MediaSourceKind
import com.mikeyphw.xdm.android.model.RecoveryAction
import com.mikeyphw.xdm.android.model.RecoveryClassification
import com.mikeyphw.xdm.android.model.VerificationStatus

fun DownloadState.uiLabel(): String = when (this) {
    DownloadState.Created -> "Created"
    DownloadState.Queued -> "Queued"
    DownloadState.Connecting -> "Connecting"
    DownloadState.Downloading -> "Downloading"
    DownloadState.Paused -> "Paused"
    DownloadState.WaitingForNetwork -> "Waiting for network"
    DownloadState.WaitingForPower -> "Waiting for power"
    DownloadState.Verifying -> "Verifying"
    DownloadState.Repairing -> "Repairing"
    DownloadState.Finalizing -> "Finishing"
    DownloadState.Completed -> "Complete"
    DownloadState.Failed -> "Failed"
    DownloadState.Cancelled -> "Cancelled"
    DownloadState.RecoveryRequired -> "Needs recovery"
}

fun DownloadState.statusTone(): XdmStatusTone = when (this) {
    DownloadState.Completed -> XdmStatusTone.Success
    DownloadState.Failed,
    DownloadState.Cancelled,
    DownloadState.RecoveryRequired -> XdmStatusTone.Error
    DownloadState.WaitingForNetwork,
    DownloadState.WaitingForPower,
    DownloadState.Paused -> XdmStatusTone.Warning
    DownloadState.Downloading,
    DownloadState.Connecting,
    DownloadState.Verifying,
    DownloadState.Repairing,
    DownloadState.Finalizing -> XdmStatusTone.Info
    DownloadState.Created,
    DownloadState.Queued -> XdmStatusTone.Neutral
}

fun BackendType.uiLabel(): String = when (this) {
    BackendType.Automatic -> "Automatic"
    BackendType.Native -> "XDM Native"
    BackendType.Aria2 -> "aria2"
}

fun ChecksumAlgorithm.uiLabel(): String = when (this) {
    ChecksumAlgorithm.Sha256 -> "SHA-256"
    ChecksumAlgorithm.Sha512 -> "SHA-512"
}

fun VerificationStatus.uiLabel(): String = when (this) {
    VerificationStatus.Pending -> "Waiting to verify"
    VerificationStatus.Running -> "Verifying"
    VerificationStatus.Passed -> "Verified"
    VerificationStatus.Failed -> "Verification failed"
    VerificationStatus.NoExpectation -> "No checksum set"
    VerificationStatus.MissingFile -> "File missing"
}

fun FilenameConflictPolicy.uiLabel(): String = when (this) {
    FilenameConflictPolicy.Overwrite -> "Overwrite"
    FilenameConflictPolicy.Resume -> "Resume"
    FilenameConflictPolicy.Rename -> "Rename"
    FilenameConflictPolicy.Skip -> "Skip"
    FilenameConflictPolicy.Compare -> "Compare"
}

fun MediaCaptureStatus.uiLabel(): String = when (this) {
    MediaCaptureStatus.Captured -> "Captured"
    MediaCaptureStatus.MetadataReady -> "Metadata ready"
    MediaCaptureStatus.MetadataMissing -> "Metadata missing"
    MediaCaptureStatus.DownloadCreated -> "Added to downloads"
    MediaCaptureStatus.Expired -> "Expired"
}

fun MediaResolutionStatus.uiLabel(): String = when (this) {
    MediaResolutionStatus.Unresolved -> "Not resolved"
    MediaResolutionStatus.Resolved -> "Resolved"
    MediaResolutionStatus.RequiresRefresh -> "Needs refresh"
    MediaResolutionStatus.Failed -> "Resolution failed"
}

fun MediaSourceKind.uiLabel(): String = when (this) {
    MediaSourceKind.DirectFile -> "File"
    MediaSourceKind.ProgressiveMedia -> "Progressive media"
    MediaSourceKind.HlsPlaylist -> "HLS playlist"
    MediaSourceKind.DashManifest -> "DASH manifest"
    MediaSourceKind.AudioStream -> "Audio stream"
    MediaSourceKind.VideoStream -> "Video stream"
    MediaSourceKind.Unknown -> "Media"
}

fun RecoveryClassification.uiLabel(): String = when (this) {
    RecoveryClassification.ReadyToResume -> "Ready to resume"
    RecoveryClassification.NeedsRemoteValidation -> "Needs validation"
    RecoveryClassification.NeedsRepair -> "Needs repair"
    RecoveryClassification.MissingPartialFile -> "Partial file missing"
    RecoveryClassification.RemoteFileChanged -> "Remote file changed"
    RecoveryClassification.CompletionRecovered -> "Completion recovered"
    RecoveryClassification.FinalizationInterrupted -> "Finishing interrupted"
    RecoveryClassification.BackendTaskOrphaned -> "Backend task orphaned"
    RecoveryClassification.OrphanedArtifact -> "Orphaned file"
}

fun RecoveryClassification.statusTone(): XdmStatusTone = when (this) {
    RecoveryClassification.ReadyToResume,
    RecoveryClassification.CompletionRecovered -> XdmStatusTone.Success
    RecoveryClassification.NeedsRemoteValidation,
    RecoveryClassification.NeedsRepair,
    RecoveryClassification.FinalizationInterrupted,
    RecoveryClassification.BackendTaskOrphaned,
    RecoveryClassification.OrphanedArtifact -> XdmStatusTone.Warning
    RecoveryClassification.MissingPartialFile,
    RecoveryClassification.RemoteFileChanged -> XdmStatusTone.Error
}

fun RecoveryAction.uiLabel(): String = when (this) {
    RecoveryAction.Resume -> "Resume"
    RecoveryAction.Validate -> "Validate"
    RecoveryAction.VerifyAndRepair -> "Verify and repair"
    RecoveryAction.RestartFromZero -> "Restart"
    RecoveryAction.AdoptOrphan -> "Adopt file"
    RecoveryAction.LocateFile -> "Locate file"
    RecoveryAction.RemoveRecord -> "Remove record"
}

fun BackendBatteryImpact.uiLabel(): String = when (this) {
    BackendBatteryImpact.Low -> "Low"
    BackendBatteryImpact.Moderate -> "Moderate"
    BackendBatteryImpact.High -> "High"
}

fun BackendDiagnosticDetail.uiLabel(): String = when (this) {
    BackendDiagnosticDetail.Basic -> "Basic"
    BackendDiagnosticDetail.Detailed -> "Detailed"
    BackendDiagnosticDetail.Forensic -> "Detailed with file checks"
}

fun BackendMigrationStage.uiLabel(): String = when (this) {
    BackendMigrationStage.Requested -> "Requested"
    BackendMigrationStage.SourcePaused -> "Source paused"
    BackendMigrationStage.SourceInspected -> "Source inspected"
    BackendMigrationStage.TargetPrepared -> "Target prepared"
    BackendMigrationStage.OwnershipTransferred -> "Ownership transferred"
    BackendMigrationStage.TargetAttached -> "Target attached"
    BackendMigrationStage.Completed -> "Complete"
    BackendMigrationStage.Failed -> "Failed"
    BackendMigrationStage.RecoveryRequired -> "Needs recovery"
}

fun enabledLabel(enabled: Boolean): String = if (enabled) "Enabled" else "Disabled"
fun enabledTone(enabled: Boolean): XdmStatusTone = if (enabled) XdmStatusTone.Success else XdmStatusTone.Neutral
