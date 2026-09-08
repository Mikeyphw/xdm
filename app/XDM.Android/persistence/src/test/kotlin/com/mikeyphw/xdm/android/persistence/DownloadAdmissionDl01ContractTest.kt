package com.mikeyphw.xdm.android.persistence

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadAdmissionDl01ContractTest {
    private val root = File(System.getProperty("user.dir") ?: ".")

    @Test
    fun admissionSerializesDuplicateDecisionAndAtomicGraphPersistence() {
        val repository = File(root, "src/main/kotlin/com/mikeyphw/xdm/android/persistence/DownloadRepository.kt").readText()
        val dao = File(root, "src/main/kotlin/com/mikeyphw/xdm/android/persistence/DownloadDao.kt").readText()

        assertTrue(repository.contains("downloadAdmissionMutex.withLock"))
        assertTrue(repository.contains("database.withTransaction"))
        assertTrue(repository.contains("OrganizationPowerTools.duplicateFor"))
        assertTrue(repository.contains("OrganizationPowerTools.duplicateActionFor"))
        assertTrue(repository.contains("upsertDownloadPreservingNewerState"))
        val transactionStart = repository.indexOf("database.withTransaction")
        val duplicateCheck = repository.indexOf("OrganizationPowerTools.duplicateFor", transactionStart)
        val downloadWrite = repository.indexOf("upsertDownloadPreservingNewerState", duplicateCheck)
        val checksumWrite = repository.indexOf("checksumDao().upsertExpectation", downloadWrite)
        assertTrue(transactionStart >= 0 && duplicateCheck > transactionStart && downloadWrite > duplicateCheck && checksumWrite > downloadWrite)
        assertTrue(repository.contains("checksumDao().upsertExpectation"))
        assertTrue(repository.contains("DownloadAdmissionResult.NeedsConfirmation"))
        assertTrue(repository.contains("DownloadAdmissionResult.OpenExisting"))
        assertTrue(repository.contains("DownloadAdmissionResult.Skipped"))
        assertTrue(repository.contains("DownloadAdmissionResult.Rejected"))
        assertTrue(repository.contains("DuplicateUrlAction.AddAgain -> Unit"))
        assertTrue(dao.contains("suspend fun listAll(): List<DownloadEntity>"))
        assertTrue(dao.contains("suspend fun listDuplicateRules(): List<DuplicateUrlRuleEntity>"))
    }
}
