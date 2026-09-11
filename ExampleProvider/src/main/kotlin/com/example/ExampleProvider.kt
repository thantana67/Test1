package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink

class ExampleProvider : MainAPI() {
    override var mainUrl = "https://archive.org"
    override var name = "Archive Movies"
    override val supportedTypes = setOf(TvType.Movie)
    override var lang = "en"
    override val hasMainPage = true

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "https://archive.org/advancedsearch.php?q=mediatype:movies+AND+collection:feature_films&fl[]=identifier&fl[]=title&rows=30&page=$page&output=json"
        val res = app.get(url).parsedSafe<ArchiveSearchResponse>()
        val items = res?.response?.docs?.mapNotNull { doc ->
            val id = doc.identifier ?: return@mapNotNull null
            newMovieSearchResponse(doc.title ?: id, id) {
                this.posterUrl = "https://archive.org/services/img/$id"
            }
        } ?: emptyList()
        return newHomePageResponse("Public Domain Movies", items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "https://archive.org/advancedsearch.php?q=mediatype:movies+AND+title:($query)&fl[]=identifier&fl[]=title&rows=30&output=json"
        val res = app.get(url).parsedSafe<ArchiveSearchResponse>()
        return res?.response?.docs?.mapNotNull { doc ->
            val id = doc.identifier ?: return@mapNotNull null
            newMovieSearchResponse(doc.title ?: id, id) {
                this.posterUrl = "https://archive.org/services/img/$id"
            }
        } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val id = url
        val meta = app.get("https://archive.org/metadata/$id").parsedSafe<ArchiveMetadata>()
        val title = meta?.metadata?.title ?: id
        return newMovieLoadResponse(title, id, TvType.Movie, id) {
            this.posterUrl = "https://archive.org/services/img/$id"
            this.plot = meta?.metadata?.description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = data
        val meta = app.get("https://archive.org/metadata/$id").parsedSafe<ArchiveMetadata>() ?: return false
        val videoFile = meta.files?.firstOrNull { it.name?.endsWith(".mp4") == true } ?: return false
        val videoUrl = "https://archive.org/download/$id/${videoFile.name}"
        callback(newExtractorLink(this.name, this.name, videoUrl))
        return true
    }
}

data class ArchiveSearchResponse(val response: ArchiveResponseBody?)
data class ArchiveResponseBody(val docs: List<ArchiveDoc>?)
data class ArchiveDoc(val identifier: String?, val title: String?)

data class ArchiveMetadata(val metadata: ArchiveMeta?, val files: List<ArchiveFile>?)
data class ArchiveMeta(val title: String?, val description: String?)
data class ArchiveFile(val name: String?)