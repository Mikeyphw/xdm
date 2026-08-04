package com.mikeyphw.xdm.android

object Phase9AccessibilityRegressionContracts {
    val riskyLargeFontSurfaces: Set<String> = setOf(
        "download-list-row",
        "download-details",
        "download-actions-menu",
        "adaptive-sheet",
        "post-processing-job-row",
        "media-variant-row",
    )

    val composeScreenshotSemanticsMatrix: List<String> = XdmAdaptiveTestMatrix.screenshotSemanticsCases.map { it.name }

    val highContrastSurfaces: Set<String> = XdmContrastPolicy.requiredSurfaceNames()

    fun coversRequiredRiskySurface(surface: String): Boolean = surface in riskyLargeFontSurfaces
    fun coversScreenshotCase(caseName: String): Boolean = caseName in composeScreenshotSemanticsMatrix
    fun coversHighContrastSurface(surface: String): Boolean = surface in highContrastSurfaces
}
