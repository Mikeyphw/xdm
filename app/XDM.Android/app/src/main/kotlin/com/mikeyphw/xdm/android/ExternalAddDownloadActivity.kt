package com.mikeyphw.xdm.android

/**
 * Dedicated external-download entry point advertised to browsers and the Android Sharesheet.
 *
 * The activity intentionally reuses MainActivity's Compose shell and ViewModel wiring. Ordinary
 * shares, typed links, and browser download actions remain review-first Add Download handoffs.
 * The XDM-owned custom scheme is parsed before that generic rule so `capture` can open the
 * existing Media review flow while `add` opens Add Download. The app topology stays unchanged.
 */
class ExternalAddDownloadActivity : MainActivity()
