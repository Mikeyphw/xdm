package com.mikeyphw.xdm.android.model

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadDuplicateAdmissionPolicyTest {
    @Test
    fun unmatchedDuplicateDefaultsToAsk() {
        assertEquals(
            DuplicateUrlAction.Ask,
            OrganizationPowerTools.duplicateActionFor("https://files.example.test/a.bin", emptyList()),
        )
    }

    @Test
    fun exactAndWildcardRulesAreAppliedDeterministically() {
        val rules = listOf(
            DuplicateUrlRule("wild", "*.example.test", DuplicateUrlAction.Skip),
            DuplicateUrlRule("exact", "files.example.test", DuplicateUrlAction.OpenExisting),
        )
        assertEquals(
            DuplicateUrlAction.OpenExisting,
            OrganizationPowerTools.duplicateActionFor("https://files.example.test/a.bin", rules),
        )
        assertEquals(
            DuplicateUrlAction.Skip,
            OrganizationPowerTools.duplicateActionFor("https://cdn.example.test/a.bin", rules),
        )
    }

    @Test
    fun disabledRulesDoNotAffectAdmission() {
        val rules = listOf(
            DuplicateUrlRule("disabled", "files.example.test", DuplicateUrlAction.AddAgain, enabled = false),
        )
        assertEquals(
            DuplicateUrlAction.Ask,
            OrganizationPowerTools.duplicateActionFor("https://files.example.test/a.bin", rules),
        )
    }
}
