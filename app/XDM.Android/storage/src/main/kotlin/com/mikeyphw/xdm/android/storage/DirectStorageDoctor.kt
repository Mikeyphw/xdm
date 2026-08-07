package com.mikeyphw.xdm.android.storage

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class DirectStorageDoctorStep(
    val name: String,
    val passed: Boolean,
    val detail: String,
)

data class DirectStorageDoctorReport(
    val directory: String,
    val passed: Boolean,
    val steps: List<DirectStorageDoctorStep>,
) {
    val summary: String = buildString {
        append(if (passed) "Direct storage passed" else "Direct storage failed")
        append(": ")
        append(steps.joinToString("; ") { step -> "${step.name}=${if (step.passed) "ok" else "failed"}" })
    }
}

object DirectStorageDoctor {
    fun run(context: Context): DirectStorageDoctorReport {
        val directory = PersonalDirectStorage.downloadsDirectory()
        if (!PersonalDirectStorage.isGranted(context)) {
            return DirectStorageDoctorReport(
                directory = directory.absolutePath,
                passed = false,
                steps = listOf(DirectStorageDoctorStep("permission", false, "Android all-files access is not granted.")),
            )
        }
        return run(directory, permissionGranted = true)
    }

    internal fun run(directory: File, permissionGranted: Boolean): DirectStorageDoctorReport {
        val steps = mutableListOf<DirectStorageDoctorStep>()
        fun record(name: String, block: () -> String) {
            val result = runCatching(block)
            steps += DirectStorageDoctorStep(
                name = name,
                passed = result.isSuccess,
                detail = result.fold({ it }, { error -> error.message ?: error::class.java.simpleName }),
            )
        }

        if (!permissionGranted) {
            return DirectStorageDoctorReport(
                directory = directory.absolutePath,
                passed = false,
                steps = listOf(DirectStorageDoctorStep("permission", false, "Direct-storage permission is missing.")),
            )
        }
        steps += DirectStorageDoctorStep("permission", true, "Direct-storage permission is available.")

        val token = UUID.randomUUID().toString()
        val probe = File(directory, ".xdm-storage-doctor-$token.tmp")
        val renamed = File(directory, ".xdm-storage-doctor-$token.ready")
        val payload = "XDM direct storage doctor $token\n".toByteArray(Charsets.UTF_8)
        try {
            record("mkdir") {
                check(directory.isDirectory || directory.mkdirs()) { "Unable to create ${directory.absolutePath}" }
                "Directory exists."
            }
            record("create") {
                check(probe.createNewFile()) { "Probe file already existed or could not be created." }
                "Probe file created."
            }
            record("write+fsync") {
                FileOutputStream(probe, false).use { output ->
                    output.write(payload)
                    output.flush()
                    output.fd.sync()
                }
                check(probe.length() == payload.size.toLong()) { "Probe byte count mismatch." }
                "${payload.size} bytes written and fsynced."
            }
            record("rename") {
                check(probe.renameTo(renamed)) { "Atomic-style rename failed." }
                "Probe renamed in-place."
            }
            record("read") {
                check(renamed.readBytes().contentEquals(payload)) { "Read-back payload mismatch." }
                "Read-back matched."
            }
            record("delete") {
                check(renamed.delete()) { "Probe cleanup failed." }
                "Probe removed."
            }
        } finally {
            probe.delete()
            renamed.delete()
        }
        return DirectStorageDoctorReport(
            directory = directory.absolutePath,
            passed = steps.all(DirectStorageDoctorStep::passed),
            steps = steps.toList(),
        )
    }
}
