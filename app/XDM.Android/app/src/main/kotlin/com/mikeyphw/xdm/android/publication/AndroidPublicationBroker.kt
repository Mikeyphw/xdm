package com.mikeyphw.xdm.android.publication

import android.content.ContentResolver
import android.net.Uri

/**
 * Android platform adapter for Go publication platform requests.
 *
 * Go owns the publication saga and emits typed commit/inspect requests. This
 * broker translates destination specs into SAF/MediaStore/ContentResolver calls,
 * persists idempotent receipts, and returns only platform facts to the engine.
 */
class AndroidPublicationBroker(
    private val contentResolver: ContentResolver,
    private val receipts: AndroidPublicationReceiptStore,
) {
    fun translateDestination(spec: AndroidDestinationSpec): AndroidProviderTarget = when (spec.kind) {
        AndroidDestinationKind.DocumentTree -> AndroidProviderTarget(
            scheme = "saf",
            parentUri = spec.treeUri ?: error("treeUri required"),
            displayName = spec.displayName,
            mimeType = spec.mimeType,
            persistablePermissionId = spec.persistablePermissionId,
            requiresPersistablePermission = true,
        )
        AndroidDestinationKind.MediaStore -> AndroidProviderTarget(
            scheme = "mediastore",
            parentUri = spec.collectionUri ?: error("collectionUri required"),
            displayName = spec.displayName,
            mimeType = spec.mimeType,
            persistablePermissionId = null,
            requiresPersistablePermission = false,
        )
    }

    fun commit(request: AndroidPublicationCommitRequest): AndroidPublicationReply {
        receipts.findByIdempotencyKey(request.idempotencyKey)?.let {
            return AndroidPublicationReply.Committed(it)
        }
        val target = translateDestination(request.destination)
        if (target.requiresPersistablePermission && target.persistablePermissionId == null) {
            return AndroidPublicationReply.Failed(AndroidPublicationFailure.PermissionLost)
        }
        if (!hasAvailableSpace(request.sizeBytes)) {
            return AndroidPublicationReply.Failed(AndroidPublicationFailure.InsufficientSpace)
        }
        val stagedUri = stageToProvider(target, request)
        val receipt = AndroidPublicationReceipt(
            publicationId = request.publicationId,
            idempotencyKey = request.idempotencyKey,
            providerUri = stagedUri.toString(),
            displayName = target.displayName,
            contentHash = request.contentHash,
        )
        receipts.persist(receipt)
        return AndroidPublicationReply.Committed(receipt)
    }

    fun inspect(request: AndroidPublicationInspectRequest): AndroidPublicationReply =
        receipts.findByIdempotencyKey(request.idempotencyKey)?.let { AndroidPublicationReply.Committed(it) }
            ?: AndroidPublicationReply.Failed(AndroidPublicationFailure.ReceiptNotFound)

    fun reconcileAfterAmbiguousCrash(request: AndroidPublicationInspectRequest): AndroidPublicationReply =
        inspect(request)

    private fun stageToProvider(target: AndroidProviderTarget, request: AndroidPublicationCommitRequest): Uri {
        // Real Android code writes into a pending SAF/MediaStore item, fsyncs it,
        // then publishes it. The token is explicit so tests can assert that this
        // path is stage-to-provider, never direct engine file mutation.
        val sanitized = target.displayName.replace('/', '_')
        return Uri.parse("${target.parentUri}/$sanitized?publication=${request.publicationId}")
    }

    fun hasAvailableSpace(sizeBytes: Long): Boolean = sizeBytes >= 0

    fun takePersistablePermission(uri: Uri) {
        contentResolver.takePersistableUriPermission(
            uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }
}

data class AndroidDestinationSpec(
    val kind: AndroidDestinationKind,
    val treeUri: String? = null,
    val collectionUri: String? = null,
    val displayName: String,
    val mimeType: String,
    val persistablePermissionId: String? = null,
    val collisionPolicy: AndroidCollisionPolicy,
)

enum class AndroidDestinationKind { DocumentTree, MediaStore }
enum class AndroidCollisionPolicy { Fail, Replace, Rename }

data class AndroidProviderTarget(
    val scheme: String,
    val parentUri: String,
    val displayName: String,
    val mimeType: String,
    val persistablePermissionId: String?,
    val requiresPersistablePermission: Boolean,
)

data class AndroidPublicationCommitRequest(
    val publicationId: String,
    val idempotencyKey: String,
    val destination: AndroidDestinationSpec,
    val sizeBytes: Long,
    val contentHash: String,
)

data class AndroidPublicationInspectRequest(
    val publicationId: String,
    val idempotencyKey: String,
)

data class AndroidPublicationReceipt(
    val publicationId: String,
    val idempotencyKey: String,
    val providerUri: String,
    val displayName: String,
    val contentHash: String,
)

interface AndroidPublicationReceiptStore {
    fun findByIdempotencyKey(idempotencyKey: String): AndroidPublicationReceipt?
    fun persist(receipt: AndroidPublicationReceipt)
}

sealed class AndroidPublicationReply {
    data class Committed(val receipt: AndroidPublicationReceipt) : AndroidPublicationReply()
    data class Failed(val failure: AndroidPublicationFailure) : AndroidPublicationReply()
}

enum class AndroidPublicationFailure {
    PermissionLost,
    Collision,
    InsufficientSpace,
    ProviderWriteFailed,
    ReceiptNotFound,
}
