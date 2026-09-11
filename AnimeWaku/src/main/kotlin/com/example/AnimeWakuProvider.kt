package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class AnimeWakuProvider : MainAPI() {

    override var mainUrl = "https://anime-waku.com"
    override var name = "AnimeWaku"
    override val supportedTypes = setOf(TvType.Anime)
    override var lang = "en"
    override val hasMainPage = true

    /*
     * IMPORTANT:
     * อย่าใส่ single quote ครอบ User-Agent
     * และอย่าใส่ตัวอักษรภาษาไทยหรืออักขระแปลก ๆ ต่อท้าย
     */
    private val userAgent =
        "Mozilla/5.0 (Linux; Android 13; K) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/152.0.0.0 Mobile Safari/537.36"

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

            /*
             * ไม่ใช้ DooPlay REST API แล้ว
             * เพราะเว็บปัจจุบันไม่ได้พึ่ง endpoint เดิมของ provider
             */
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

        /*
         * Anime-Waku ใช้ URL:
         * /anime/ชื่อเรื่อง/
         *
         * จึงไม่จำเป็นต้องพึ่ง selector ของ DooPlay
         */
        document.select("a[href*='/anime/']").forEach { element ->

            val href = fixUrlNull(element.attr("href")) ?: return@forEach

            if (!href.contains("/anime/")) return@forEach

            /*
             * ป้องกัน duplicate
             */
            if (!seen.add(href)) return@forEach

            /*
             * ข้อความจาก link
             */
            var title = element.text().trim()

            /*
             * ถ้า link ไม่มีข้อความ ลองใช้ alt ของ image
             */
            if (title.isBlank()) {
                title = element
                    .selectFirst("img")
                    ?.attr("alt")
                    ?.trim()
                    ?: ""
            }

            if (title.isBlank()) return@forEach

            /*
             * ล้างข้อความ status ที่เว็บแสดงต่อท้าย
             */
            title = cleanAnimeTitle(title)

            /*
             * หา poster
             */
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

        /*
         * Title
         */
        val title = (
                document.selectFirst("h1")?.text()
                    ?: document.title()
                    ?: "Unknown"
                ).trim()

        /*
         * Poster
         */
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

        /*
         * Description
         *
         * หน้าเว็บปัจจุบันมี section "เรื่องย่อ"
         */
        val description = findDescription(document)

        val subEpisodes = mutableListOf<Episode>()
        val dubEpisodes = mutableListOf<Episode>()

        /*
         * --------------------------------------------------------
         * Episode parsing
         * --------------------------------------------------------
         *
         * Anime-Waku ใช้:
         * /ep/XXXXX/
         *
         * ตัวอย่างจริง:
         * /ep/110539/
         * /ep/110541/
         */
        val episodeLinks = document.select("a[href*='/ep/']")

        val seenEpisodes = hashSetOf<String>()

        episodeLinks.forEach { element ->

            val href = fixUrlNull(element.attr("href"))
                ?: return@forEach

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

            /*
             * หาเลขตอนจากข้อความ
             */
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

            /*
             * Poster ของ episode
             */
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

            /*
             * แยกพากย์ / ซับ จากชื่อ episode
             */
            if (
                episodeName.contains(
                    "พากย์ไทย",
                    ignoreCase = true
                )
            ) {
                dubEpisodes.add(episode)
            } else {
                subEpisodes.add(episode)
            }
        }

        /*
         * เรียงเลขตอน
         */
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

    // ------------------------------------------------------------
    // DESCRIPTION
    // ------------------------------------------------------------

    private fun findDescription(
        document: org.jsoup.nodes.Document
    ): String? {

        /*
         * วิธีที่ 1:
         * หา element ที่อยู่ใต้ heading "เรื่องย่อ"
         */
        val synopsisHeading = document
            .select("h2, h3, h4")
            .firstOrNull {
                it.text().contains(
                    "เรื่องย่อ",
                    ignoreCase = true
                )
            }

        if (synopsisHeading != null) {

            val next = synopsisHeading.nextElementSibling()

            if (next != null) {
                val text = next.text().trim()

                if (text.isNotBlank()) {
                    return text
                }
            }
        }

        /*
         * วิธีที่ 2:
         * fallback หา paragraph ที่ยาว
         */
        return document
            .select("p")
            .map { it.text().trim() }
            .firstOrNull {
                it.length > 50 &&
                        !it.contains("Login to your account")
            }
    }

    // ------------------------------------------------------------
    // LOAD LINKS
    // ------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        return try {

            val document = app.get(
                url = data,
                headers = defaultHeaders
            ).document

            /*
             * Anime-Waku episode page
             *
             * หน้า episode จะมี player หลัก / สำรอง
             *
             * พยายามหา iframe ทุกตัว
             */
            val iframes = document.select("iframe")

            for (iframe in iframes) {

                val src = iframe.attr("src").trim()

                if (src.isBlank()) continue

                val embedUrl = fixUrlNull(src)
                    ?: continue

                try {

                    loadExtractor(
                        embedUrl,
                        data,
                        subtitleCallback,
                        callback
                    )

                } catch (_: Exception) {
                    // ข้าม player ที่ extractor ไม่รองรับ
                }
            }

            /*
             * กรณีเว็บใส่ player URL อยู่ใน data-src
             */
            val dataSrcPlayers = document.select(
                "[data-src], [data-url]"
            )

            for (player in dataSrcPlayers) {

                val raw =
                    player.attr("data-src").ifBlank {
                        player.attr("data-url")
                    }

                if (raw.isBlank()) continue

                val playerUrl = fixUrlNull(raw)
                    ?: continue

                if (
                    !playerUrl.startsWith("http://") &&
                    !playerUrl.startsWith("https://")
                ) {
                    continue
                }

                try {

                    loadExtractor(
                        playerUrl,
                        data,
                        subtitleCallback,
                        callback
                    )

                } catch (_: Exception) {
                    // ข้าม player ที่ extractor ไม่รองรับ
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