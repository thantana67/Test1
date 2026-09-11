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

    // กำหนด Headers ให้ตรงกับ Android Chrome เต็มรูปแบบ
    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "th-TH,th;q=0.9,en-US;q=0.8,en;q=0.7",
        "Sec-Ch-Ua" to "\"Not-A.Brand\";v=\"99\", \"Chromium\";v=\"124\"",
        "Sec-Ch-Ua-Mobile" to "?1",
        "Sec-Ch-Ua-Platform" to "\"Android\"",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none",
        "Sec-Fetch-User" to "?1",
        "Upgrade-Insecure-Requests" to "1"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "อนิเมะอัปเดตล่าสุด"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) mainUrl else "$mainUrl/page/$page/"

        val res = app.get(
            url = url,
            headers = defaultHeaders
        )

        val document = res.document
        val elements = document.select("article.item, div.items article, .content .items .item")
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

    // แก้ไขระบบ Search ให้ใช้ DooPlay Live Search REST API โดยตรง
    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            // วิธีที่ 1: ยิงผ่าน Search API ประจำธีม DooPlay
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val apiUrl = "$mainUrl/wp-json/dooplay/search/?keyword=$encodedQuery&nonce=1173638aff"
            val apiRes = app.get(apiUrl, headers = defaultHeaders)

            if (apiRes.code == 200 && apiRes.text.contains("title")) {
                val doc = Jsoup.parse(apiRes.text)
                doc.select("li, div.result-item, a").mapNotNull { it.toSearchResult() }
            } else {
                // วิธีที่ 2: ถ้า API ไม่ตอบกลับ ให้ Fallback ใช้หน้าเว็บค้นหาปกติ
                val doc = app.get("$mainUrl/?s=$encodedQuery", headers = defaultHeaders).document
                doc.select("div.items article, #archive-content article, .result-item, article.item").mapNotNull {
                    it.toSearchResult()
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = defaultHeaders).document

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
        val document = app.get(data, headers = defaultHeaders).document
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
                headers = defaultHeaders + mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to data
                )
            ).text

            val wrapperUrl = fixUrlNull(Jsoup.parse(ajaxHtml).selectFirst("iframe")?.attr("src")) ?: continue

            val wrapperDoc = app.get(
                url = wrapperUrl,
                referer = data,
                headers = defaultHeaders
            ).document

            val embedUrl = fixUrlNull(wrapperDoc.selectFirst("iframe#embedvideo")?.attr("src")) ?: continue

            loadExtractor(embedUrl, wrapperUrl, subtitleCallback, callback)
        }
        return true
    }
}