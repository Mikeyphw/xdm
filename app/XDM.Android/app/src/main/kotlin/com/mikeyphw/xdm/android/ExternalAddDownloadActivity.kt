package com.mikeyphw.xdm.android

/**
 * Dedicated external-download entry point advertised to browsers and the Android Sharesheet.
 *
 * The activity intentionally reuses MainActivity's Compose shell and ViewModel wiring, but
 * MainActivity detects this component and routes the incoming link to the Add Download prompt
 * instead of trying media capture first. This mirrors dedicated receiver patterns used by
 * Android download managers while keeping the normal app topology unchanged.
 */
class ExternalAddDownloadActivity : MainActivity()
