package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class AnimeWakuProvider : MainAPI() {

    override var mainUrl = "https://anime-waku.com"
    override var name = "AnimeWaku"
    override val supportedTypes = setOf(TvType.Anime)
    override var lang = "th"
    override val hasMainPage = true

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val defaultHeaders = mapOf(
        "User-Agent" to userAgent,
        "Referer" to "$mainUrl/",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9," +
                "image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "th-TH,th;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/anime/?get=anime" to "อนิเมะอัปเดตล่าสุด"
    )

    // ------------------------------------------------------------
    // MAIN PAGE
    // ------------------------------------------------------------

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val url = when {
            page <= 1 -> "$mainUrl/anime/?get=anime"
            else -> "$mainUrl/anime/page/$page/?get=anime"
        }

        return try {
            val document = app.get(
                url = url,
                headers = defaultHeaders
            ).document

            val results = parseAnimeLinks(document)

            newHomePageResponse(
                request.name,
                results,
                hasNext = results.isNotEmpty()
            )
        } catch (e: Exception) {
            newHomePageResponse(
                request.name,
                emptyList(),
                hasNext = false
            )
        }
    }

    // ------------------------------------------------------------
    // SEARCH
    // ------------------------------------------------------------

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        return try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "$mainUrl/?s=$encodedQuery"

            val document = app.get(
                url = url,
                headers = defaultHeaders
            ).document

            parseAnimeLinks(document)

        } catch (e: Exception) {
            emptyList()
        }
    }

    // ------------------------------------------------------------
    // PARSE ANIME CARD / SEARCH RESULT
    // ------------------------------------------------------------

    private fun parseAnimeLinks(
        document: org.jsoup.nodes.Document
    ): List<SearchResponse> {

        val results = mutableListOf<SearchResponse>()
        val seen = hashSetOf<String>()

        document.select("a[href*='/anime/']").forEach { element ->

            val href = fixUrlNull(element.attr("href")) ?: return@forEach

            if (!href.contains("/anime/")) return@forEach
            if (!seen.add(href)) return@forEach

            var title = element.text().trim()

            if (title.isBlank()) {
                title = element
                    .selectFirst("img")
                    ?.attr("alt")
                    ?.trim()
                    ?: ""
            }

            if (title.isBlank()) return@forEach

            title = cleanAnimeTitle(title)

            val image = element.selectFirst("img")
            val rawPoster =
                image?.attr("data-lazy-src")?.takeIf { it.isNotBlank() }
                    ?: image?.attr("data-src")?.takeIf { it.isNotBlank() }
                    ?: image?.attr("data-original")?.takeIf { it.isNotBlank() }
                    ?: image?.attr("src")?.takeIf { it.isNotBlank() }

            val poster = fixUrlNull(rawPoster)

            results.add(
                newAnimeSearchResponse(
                    title,
                    href,
                    TvType.Anime
                ) {
                    posterUrl = poster
                }
            )
        }

        return results
    }

    private fun cleanAnimeTitle(
        title: String
    ): String {

        return title
            .replace(Regex("\\s+"), " ")
            .replace(
                Regex(
                    "\\s*ตอนที่\\s*\\d+(?:-\\d+)?\\s*" +
                            "(?:ซับไทย|พากย์ไทย)?\\s*" +
                            "(?:และ\\s*(?:พากย์ไทย|ซับไทย))?\\s*" +
                            "(?:ยังไม่จบ|จบแล้ว)?"
                ),
                ""
            )
            .trim()
    }

    // ------------------------------------------------------------
    // LOAD ANIME DETAIL
    // ------------------------------------------------------------

    override suspend fun load(
        url: String
    ): LoadResponse {

        val document = app.get(
            url = url,
            headers = defaultHeaders
        ).document

        val title = (
                document.selectFirst("h1")?.text()
                    ?: document.title()
                    ?: "Unknown"
                ).trim()

        val posterElement = document.selectFirst(
            "img[alt*='${escapeCss(title)}'], " +
                    ".poster img, " +
                    ".entry-content img, " +
                    "article img, " +
                    "img"
        )

        val rawPoster =
            posterElement?.attr("data-lazy-src")?.takeIf { it.isNotBlank() }
                ?: posterElement?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: posterElement?.attr("data-original")?.takeIf { it.isNotBlank() }
                ?: posterElement?.attr("src")?.takeIf { it.isNotBlank() }

        val poster = fixUrlNull(rawPoster)
        val description = findDescription(document)

        val subEpisodes = mutableListOf<Episode>()
        val dubEpisodes = mutableListOf<Episode>()

        val episodeLinks = document.select("a[href*='/ep/']")
        val seenEpisodes = hashSetOf<String>()

        episodeLinks.forEach { element ->

            val href = fixUrlNull(element.attr("href")) ?: return@forEach

            if (!href.contains("/ep/")) return@forEach
            if (!seenEpisodes.add(href)) return@forEach

            var episodeName = element.text().trim()

            if (episodeName.isBlank()) {
                episodeName = element
                    .selectFirst("img")
                    ?.attr("alt")
                    ?.trim()
                    ?: ""
            }

            if (episodeName.isBlank()) return@forEach

            val episodeNumber = Regex(
                "ตอนที่\\s*(\\d+)"
            )
                .find(episodeName)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?: Regex(
                    "(?:-|–)\\s*(\\d+)"
                )
                    .find(episodeName)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                ?: (subEpisodes.size + dubEpisodes.size + 1)

            val epImage = element.selectFirst("img")
            val rawEpPoster =
                epImage?.attr("data-lazy-src")?.takeIf { it.isNotBlank() }
                    ?: epImage?.attr("data-src")?.takeIf { it.isNotBlank() }
                    ?: epImage?.attr("data-original")?.takeIf { it.isNotBlank() }
                    ?: epImage?.attr("src")?.takeIf { it.isNotBlank() }

            val epPoster = fixUrlNull(rawEpPoster)

            val episode = newEpisode(href) {
                name = episodeName
                episode = episodeNumber
                posterUrl = epPoster
            }

            if (episodeName.contains("พากย์ไทย", ignoreCase = true)) {
                dubEpisodes.add(episode)
            } else {
                subEpisodes.add(episode)
            }
        }

        subEpisodes.sortBy { it.episode }
        dubEpisodes.sortBy { it.episode }

        return newAnimeLoadResponse(
            title,
            url,
            TvType.Anime
        ) {
            posterUrl = poster
            plot = description

            if (subEpisodes.isNotEmpty()) {
                addEpisodes(
                    DubStatus.Subbed,
                    subEpisodes
                )
            }

            if (dubEpisodes.isNotEmpty()) {
                addEpisodes(
                    DubStatus.Dubbed,
                    dubEpisodes
                )
            }
        }
    }

    private fun findDescription(
        document: org.jsoup.nodes.Document
    ): String? {

        val synopsisHeading = document
            .select("h2, h3, h4")
            .firstOrNull {
                it.text().contains("เรื่องย่อ", ignoreCase = true)
            }

        if (synopsisHeading != null) {
            val next = synopsisHeading.nextElementSibling()
            if (next != null) {
                val text = next.text().trim()
                if (text.isNotBlank()) return text
            }
        }

        return document
            .select("p")
            .map { it.text().trim() }
            .firstOrNull {
                it.length > 50 && !it.contains("Login to your account")
            }
    }

    // ------------------------------------------------------------
    // LOAD LINKS (เน้นตัวเล่นที่ 2 และเจาะ HLS DooDee)
    // ------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val document = app.get(url = data, headers = defaultHeaders).document

            // คัดกรองตัวเลือก: โฟกัสตัวเล่นที่ 2 ก่อน (หลบตัวเล่น 1 ที่มี Anti-DevTools)
            val allOptions = document.select("ul#playeroptionsul li, li.dooplay_player_option")
            val targetOptions = allOptions.filter {
                it.text().contains("ตัวเล่นที่ 2") ||
                        it.text().contains("ตัวเล่น 2") ||
                        it.attr("data-nume") == "2"
            }.ifEmpty { allOptions } // ถ้าหาตัวเล่น 2 ไม่เจอ ให้ fallback เป็นทั้งหมด

            for (option in targetOptions) {
                try {
                    val postId = option.attr("data-post").trim()
                    val nume = option.attr("data-nume").trim()
                    val type = option.attr("data-type").trim()

                    if (postId.isEmpty() || nume.isEmpty()) continue

                    // 1. เรียก AJAX ของ DooPlay
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

                    val wrapperDoc = org.jsoup.Jsoup.parse(ajaxHtml)
                    val rawWrapperUrl = wrapperDoc.selectFirst("iframe")?.attr("src") ?: continue
                    val wrapperUrl = fixUrlNull(rawWrapperUrl) ?: continue

                    // 2. ดึงหน้า Player Wrapper
                    val playerDoc = app.get(wrapperUrl, referer = data, headers = defaultHeaders)
                    val playerHtml = playerDoc.text
                    val innerIframe = playerDoc.document.selectFirst("iframe#embedvideo, iframe")?.attr("src")
                    val finalUrl = fixUrlNull(innerIframe) ?: wrapperUrl

                    val finalHtml = if (finalUrl != wrapperUrl) {
                        try {
                            app.get(finalUrl, referer = wrapperUrl, headers = defaultHeaders).text
                        } catch (_: Exception) {
                            playerHtml
                        }
                    } else {
                        playerHtml
                    }

                    // 3. สกัดหา Direct URL ของ .txt หรือ .m3u8 ที่มีอยู่
                    val txtRegex = Regex("""https?://[^"'<>\s]+\/m3u8\/[a-fA-F0-9]{32}-[0-9]+\.txt""")
                    val foundDirect = txtRegex.findAll(finalHtml).toList()

                    if (foundDirect.isNotEmpty()) {
                        foundDirect.forEach { match ->
                            val streamUrl = match.value
                            val quality = when {
                                streamUrl.contains("1080") -> Qualities.P1080.value
                                streamUrl.contains("720") -> Qualities.P720.value
                                streamUrl.contains("480") -> Qualities.P480.value
                                else -> Qualities.Unknown.value
                            }

                            val qName = when (quality) {
                                Qualities.P1080.value -> "1080p"
                                Qualities.P720.value -> "720p"
                                Qualities.P480.value -> "480p"
                                else -> "Auto"
                            }

                            callback.invoke(
                                newExtractorLink(
                                    source = name,
                                    name = "$name DooDee ($qName)",
                                    url = streamUrl,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.referer = finalUrl
                                    this.quality = quality
                                }
                            )
                        }
                    } else {
                        // 4. สแกนหา Hash 32 ตัวอักษรเพื่อประกอบ URL สตรีม
                        val hashRegex = Regex("""[a-fA-F0-9]{32}""")
                        val hostDomain = Regex("""https?://[a-zA-Z0-9.-]*doodee-player\.com""").find(finalUrl)?.value
                            ?: "https://player-ok-goal.doodee-player.com"

                        val matchedHash = hashRegex.find(finalHtml)?.value
                        if (matchedHash != null) {
                            listOf("720", "1080", "480").forEach { q ->
                                val generatedUrl = "$hostDomain/m3u8/$matchedHash-$q.txt"
                                callback.invoke(
                                    newExtractorLink(
                                        source = name,
                                        name = "$name DooDee $q" + "p",
                                        url = generatedUrl,
                                        type = ExtractorLinkType.M3U8
                                    ) {
                                        this.referer = finalUrl
                                        this.quality = when (q) {
                                            "1080" -> Qualities.P1080.value
                                            "720" -> Qualities.P720.value
                                            else -> Qualities.P480.value
                                        }
                                    }
                                )
                            }
                        }
                    }

                    // สำรองตัวเล่นกรณีเป็น Extractor มาตรฐาน
                    try {
                        loadExtractor(finalUrl, wrapperUrl, subtitleCallback, callback)
                    } catch (_: Exception) { }

                } catch (_: Exception) {
                    // ลูปตัวนี้มีปัญหา ให้ข้ามไปรอบถัดไปทันที
                    continue
                }
            }

            true
        } catch (_: Exception) {
            false
        }
    }

    // ------------------------------------------------------------
    // SMALL HELPER
    // ------------------------------------------------------------

    private fun escapeCss(
        text: String
    ): String {

        return text
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\"", "\\\"")
    }
}