package com.mikeyphw.xdm.android.storage

import com.mikeyphw.xdm.android.model.DestinationType

data class DestinationChoice(
    val uri: String,
    val label: String,
    val description: String,
    val type: DestinationType,
    val minimumApi: Int = 26,
)

object DestinationCatalog {
    val builtIn = listOf(
        DestinationChoice(DestinationUris.PUBLIC_DOWNLOADS, "Public Downloads", "Visible to file managers and other apps", DestinationType.PublicDownloads, minimumApi = 29),
        DestinationChoice(DestinationUris.APP_PRIVATE_DOWNLOADS, "App-private Downloads", "Private to XDM; useful for temporary or sensitive files", DestinationType.AppPrivate),
        DestinationChoice(DestinationUris.MEDIA_MOVIES, "Movies", "Publish video files through MediaStore", DestinationType.MediaStoreMovies, minimumApi = 29),
        DestinationChoice(DestinationUris.MEDIA_MUSIC, "Music", "Publish audio files through MediaStore", DestinationType.MediaStoreMusic, minimumApi = 29),
        DestinationChoice(DestinationUris.MEDIA_PICTURES, "Pictures", "Publish images through MediaStore", DestinationType.MediaStorePictures, minimumApi = 29),
        DestinationChoice(DestinationUris.MEDIA_DOCUMENTS, "Documents", "Publish general documents through MediaStore", DestinationType.MediaStoreDocuments, minimumApi = 29),
    )

    fun available(apiLevel: Int): List<DestinationChoice> = builtIn.filter { apiLevel >= it.minimumApi }
}
