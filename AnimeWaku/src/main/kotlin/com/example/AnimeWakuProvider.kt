package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder

class AnimeWakuProvider : MainAPI() {
    override var mainUrl = "https://anime-waku.com"
    override var name = "AnimeWaku"
    override val supportedTypes = setOf(TvType.Anime)
    override var lang = "th"
    override val hasMainPage = true

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "อนิเมะอัปเดตล่าสุด",
        "$mainUrl/catalog/${URLEncoder.encode("ซับไทย", "UTF-8")}/page/" to "อนิเมะซับไทย",
        "$mainUrl/catalog/${URLEncoder.encode("พากย์ไทย", "UTF-8")}/page/" to "อนิเมะพากย์ไทย"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val requestUrl = if (page <= 1) {
            request.data.removeSuffix("page/")
        } else {
            "${request.data}$page/"
        }

        val res = app.get(
            url = requestUrl,
            headers = mapOf(
                "User-Agent" to userAgent,
                "Referer" to "$mainUrl/"
            )
        )

        val document = res.document

        // คลุม Selector ทุกแบบของ DooPlay
        val elements = document.select("div.items article, #archive-content article, .content .items .item, article.item")
        val homeItems = elements.mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, homeItems)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst(".data h3 a, h3 a, .title a, header h2 a") ?: return null
        val title = titleElement.text().trim()
        val href = fixUrlNull(titleElement.attr("href")) ?: fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null

        val img = this.selectFirst(".poster img, img")
        val rawPoster = img?.attr("data-lazy-src")?.ifEmpty { null }
            ?: img?.attr("data-src")?.ifEmpty { null }
            ?: img?.attr("src")?.ifEmpty { null }
        val posterUrl = fixUrlNull(rawPoster)

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val document = app.get(
            url = "$mainUrl/?s=$encodedQuery",
            headers = mapOf("User-Agent" to userAgent)
        ).document

        return document.select("div.items article, #archive-content article, .result-item, .content .items .item, article.item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(
            url = url,
            headers = mapOf("User-Agent" to userAgent)
        ).document

        val title = document.selectFirst(".sheader .data h1")?.text()
            ?: document.selectFirst("h1")?.text()
            ?: "Unknown"

        val img = document.selectFirst(".sheader .poster img, .poster img")
        val rawPoster = img?.attr("data-lazy-src")?.ifEmpty { null }
            ?: img?.attr("data-src")?.ifEmpty { null }
            ?: img?.attr("src")?.ifEmpty { null }
        val poster = fixUrlNull(rawPoster)

        val description = document.selectFirst("#episodes .wp-content p")?.text()

        val subEpisodes = mutableListOf<Episode>()
        val dubEpisodes = mutableListOf<Episode>()

        document.select("#seasons .se-c").forEach { seasonBlock ->
            val seasonTitle = seasonBlock.selectFirst(".se-q")?.text() ?: ""
            val isDub = seasonTitle.contains("พากย์ไทย")

            val epElements = seasonBlock.select("ul.episodios li")
            val targetList = if (isDub) dubEpisodes else subEpisodes

            epElements.forEachIndexed { index, ep ->
                val epHref = fixUrlNull(ep.selectFirst(".episodiotitle a")?.attr("href")) ?: return@forEachIndexed
                val epName = ep.selectFirst(".episodiotitle a")?.text() ?: "Episode ${index + 1}"

                val epImg = ep.selectFirst("img")
                val rawEpPoster = epImg?.attr("data-lazy-src")?.ifEmpty { null }
                    ?: epImg?.attr("data-src")?.ifEmpty { null }
                    ?: epImg?.attr("src")?.ifEmpty { null }
                val epPoster = fixUrlNull(rawEpPoster)

                targetList.add(newEpisode(epHref) {
                    this.name = epName
                    this.episode = index + 1
                    this.posterUrl = epPoster
                })
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            if (subEpisodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEpisodes)
            if (dubEpisodes.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEpisodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(
            url = data,
            headers = mapOf("User-Agent" to userAgent)
        ).document
        val playerOptions = document.select("ul#playeroptionsul li.dooplay_player_option")

        for (option in playerOptions) {
            val postId = option.attr("data-post")
            val nume = option.attr("data-nume")
            val type = option.attr("data-type")

            if (postId.isEmpty() || nume.isEmpty()) continue

            val ajaxHtml = app.post(
                url = "$mainUrl/wp-admin/admin-ajax.php",
                data = mapOf(
                    "action" to "doo_player_ajax",
                    "post" to postId,
                    "nume" to nume,
                    "type" to type
                ),
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to data,
                    "User-Agent" to userAgent
                )
            ).text

            val wrapperUrl = fixUrlNull(Jsoup.parse(ajaxHtml).selectFirst("iframe")?.attr("src")) ?: continue

            val wrapperDoc = app.get(
                url = wrapperUrl,
                referer = data,
                headers = mapOf(
                    "User-Agent" to userAgent
                )
            ).document

            val embedUrl = fixUrlNull(wrapperDoc.selectFirst("iframe#embedvideo")?.attr("src")) ?: continue

            loadExtractor(embedUrl, wrapperUrl, subtitleCallback, callback)
        }
        return true
    }
}