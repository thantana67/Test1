package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class AnimeWakuProvider : MainAPI() {
    override var mainUrl = "https://anime-waku.com"
    override var name = "AnimeWaku"
    override val supportedTypes = setOf(TvType.Anime)
    override var lang = "th"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "อนิเมะอัปเดตล่าสุด",
        "$mainUrl/catalog/ซับไทย/page/" to "อนิเมะซับไทย",
        "$mainUrl/catalog/พากย์ไทย/page/" to "อนิเมะพากย์ไทย"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // หน้า 1 ให้ดึง URL หลักตรงๆ ถ้าหน้าถัดไปค่อยต่อ /page/X/
        val url = if (page <= 1) {
            request.data.removeSuffix("page/")
        } else {
            "${request.data}$page/"
        }

        val document = app.get(
            url = url,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )
        ).document

        // ดึงการ์ดอนิเมะของ DooPlay
        val homeItems = document.select("div.items article.item, .content .items article, div.item.tvshows, div.item.movies").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, homeItems)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // ดึงชื่อเรื่อง
        val title = this.selectFirst(".data h3 a, h3 a, .title a")?.text() ?: return null

        // ดึงลิงก์ไปหน้าอนิเมะ
        val href = fixUrlNull(this.selectFirst(".data h3 a, .poster a, a")?.attr("href")) ?: return null

        // ดึงรูปโปสเตอร์ (แก้จุด Lazy load ของ DooPlay/WP Rocket)
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
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("div.items article, .result-item, .content .items .item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst(".sheader .data h1")?.text()
            ?: document.selectFirst("h1")?.text()
            ?: "Unknown"

        val poster = fixUrlNull(document.selectFirst(".sheader .poster img")?.let {
            it.attr("data-lazy-src").ifEmpty { it.attr("src") }
        })

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
                val epPoster = fixUrlNull(ep.selectFirst("img")?.let {
                    it.attr("data-lazy-src").ifEmpty { it.attr("src") }
                })

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
        val document = app.get(data).document
        val playerOptions = document.select("ul#playeroptionsul li.dooplay_player_option")

        for (option in playerOptions) {
            val postId = option.attr("data-post")
            val nume = option.attr("data-nume")
            val type = option.attr("data-type")

            if (postId.isEmpty() || nume.isEmpty()) continue

            // ขั้นตอนที่ 1: เรียก AJAX ของ DooPlay เพื่อเอา wrapper iframe
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
                    "Referer" to data
                )
            ).text

            val wrapperUrl = fixUrlNull(Jsoup.parse(ajaxHtml).selectFirst("iframe")?.attr("src")) ?: continue

            // ขั้นตอนที่ 2: โหลดหน้า wrapper พร้อม Referer เพื่อข้าม Cloudflare WAF
            val wrapperDoc = app.get(
                url = wrapperUrl,
                referer = data,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                )
            ).document

            // ขั้นตอนที่ 3: ดึง iframe embedvideo (DooDee Player)
            val embedUrl = fixUrlNull(wrapperDoc.selectFirst("iframe#embedvideo")?.attr("src")) ?: continue

            // ขั้นตอนที่ 4: ส่งต่อให้ Extractor ถอดสตรีมวิดีโอ
            loadExtractor(embedUrl, wrapperUrl, subtitleCallback, callback)
        }
        return true
    }
}