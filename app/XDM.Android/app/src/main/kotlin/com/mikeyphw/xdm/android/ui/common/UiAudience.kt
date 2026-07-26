package com.mikeyphw.xdm.android

/** Identifies who a UI surface is designed for during the staged UIX migration. */
enum class UiAudience {
    User,
    Advanced,
    Developer,
}

/**
 * Source-level boundary used by UI contract tests and review tooling.
 *
 * The annotation is intentionally retained in source only. It documents the
 * intended audience without adding runtime reflection or navigation behavior.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class UiSurface(
    val audience: UiAudience,
    val purpose: String,
)
