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
            val document = app.get(url = url, headers = defaultHeaders).document
            val results = parseAnimeLinks(document)
            newHomePageResponse(request.name, results, hasNext = results.isNotEmpty())
        } catch (e: Exception) {
            newHomePageResponse(request.name, emptyList(), hasNext = false)
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
        val document = app.get(url, headers = defaultHeaders).document

        val title = document.selectFirst("h1")?.text()?.trim() ?: "Unknown"
        val poster = fixUrlNull(document.selectFirst(".poster img, .entry-content img, img")?.attr("src"))
        val description = findDescription(document)

        val subEpisodes = mutableListOf<Episode>()
        val dubEpisodes = mutableListOf<Episode>()

        // ดึงลิงก์ทุกตัวที่มี /ep/ ในหน้าอนิเมะ
        document.select("a[href*='/ep/']").forEach { element ->
            val href = fixUrlNull(element.attr("href")) ?: return@forEach
            val episodeName = element.text().trim().ifBlank { "ตอน" }

            // ดึงตัวเลขตอนจากชื่อหรือลิงก์
            val episodeNumber = Regex("ตอนที่\\s*(\\d+)").find(episodeName)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("/ep/(\\d+)").find(href)?.groupValues?.get(1)?.toIntOrNull()
                ?: (subEpisodes.size + dubEpisodes.size + 1)

            val episode = newEpisode(href) {
                name = episodeName
                episode = episodeNumber
            }

            if (episodeName.contains("พากย์ไทย", ignoreCase = true)) {
                dubEpisodes.add(episode)
            } else {
                subEpisodes.add(episode)
            }
        }

        subEpisodes.sortBy { it.episode }
        dubEpisodes.sortBy { it.episode }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            posterUrl = poster
            plot = description
            if (subEpisodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEpisodes)
            if (dubEpisodes.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEpisodes)
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
    // LOAD LINKS (Step-by-Step Diagnostic Mode)
    // ------------------------------------------------------------

//    override suspend fun loadLinks(
//        data: String,
//        isCasting: Boolean,
//        subtitleCallback: (SubtitleFile) -> Unit,
//        callback: (ExtractorLink) -> Unit
//    ): Boolean {
//        // ขั้นตอนที่ 1: ดึงหน้า Episode (ใส่ try ให้ครบ)
//        val document = try {
//            app.get(url = data, headers = defaultHeaders).document
//        } catch (e: Exception) {
//            throw ErrorLoadingException("สเต็ป 1 ล้มเหลว: โหลดหน้าตอนไม่ได้ (${e.message})")
//        }
//
//        // ขั้นตอนที่ 2: ค้นหาแท็บปุ่ม Player
//        val allOptions = document.select("ul#playeroptionsul li, li.dooplay_player_option")
//        if (allOptions.isEmpty()) {
//            throw ErrorLoadingException("สเต็ป 2 ล้มเหลว: หาปุ่มตัวเลือก Player ไม่พบใน DOM")
//        }
//
//        val targetOption = allOptions.find {
//            it.text().contains("2") || it.attr("data-nume") == "2"
//        } ?: allOptions.first()!!
//
//        val postId = targetOption.attr("data-post").trim()
//        val nume = targetOption.attr("data-nume").trim()
//        val type = targetOption.attr("data-type").trim()
//
//        if (postId.isEmpty() || nume.isEmpty()) {
//            throw ErrorLoadingException("สเต็ป 2.1 ล้มเหลว: Attribute ไม่ครบ (post='$postId', nume='$nume')")
//        }
//
//        // ขั้นตอนที่ 3: ส่ง AJAX ขอ Iframe
//        val ajaxRes = try {
//            app.post(
//                url = "$mainUrl/wp-admin/admin-ajax.php",
//                data = mapOf(
//                    "action" to "doo_player_ajax",
//                    "post" to postId,
//                    "nume" to nume,
//                    "type" to type
//                ),
//                headers = defaultHeaders + mapOf(
//                    "X-Requested-With" to "XMLHttpRequest",
//                    "Referer" to data
//                )
//            )
//        } catch (e: Exception) {
//            throw ErrorLoadingException("สเต็ป 3 ล้มเหลว: ยิง AJAX ไม่สำเร็จ (${e.message})")
//        }
//
//        val rawIframe = org.jsoup.Jsoup.parse(ajaxRes.text).selectFirst("iframe")?.attr("src")
//            ?: throw ErrorLoadingException("สเต็ป 3.1 ล้มเหลว: AJAX ไม่ส่งแท็ก iframe กลับมา (ตอบกลับ: '${ajaxRes.text.take(80)}')")
//
//        val wrapperUrl = fixUrlNull(rawIframe)
//            ?: throw ErrorLoadingException("สเต็ป 3.2 ล้มเหลว: แปลง URL iframe ไม่สำเร็จ ($rawIframe)")
//
//        // ขั้นตอนที่ 4: โหลดหน้า Wrapper ของ DooDee
//        val playerDoc = try {
//            app.get(wrapperUrl, referer = data, headers = defaultHeaders)
//        } catch (e: Exception) {
//            throw ErrorLoadingException("สเต็ป 4 ล้มเหลว: ดึงหน้า DooDee Wrapper ไม่สำเร็จ (${e.message})")
//        }
//
//        val playerHtml = playerDoc.text
//        val innerIframe = playerDoc.document.selectFirst("iframe#embedvideo, iframe")?.attr("src")
//        val finalUrl = fixUrlNull(innerIframe) ?: wrapperUrl
//
//        val finalHtml = if (finalUrl != wrapperUrl) {
//            try {
//                app.get(finalUrl, referer = wrapperUrl, headers = defaultHeaders).text
//            } catch (e: Exception) {
//                playerHtml
//            }
//        } else {
//            playerHtml
//        }
//
//        // ขั้นตอนที่ 5: พ่นผลลัพธ์เพื่อวินิจฉัยจุดที่ Hash ซ่อนอยู่
//        val txtFound = Regex("""https?://[^"'<>\s]+\/m3u8\/[a-fA-F0-9]{32}-[0-9]+\.txt""").find(finalHtml)?.value
//        val hashFound = Regex("""[a-fA-F0-9]{32}""").find(finalHtml)?.value
//
//        throw ErrorLoadingException(
//            "วินิจฉัยหน้าสำเร็จ!\n" +
//                    "• Final URL: $finalUrl\n" +
//                    "• Direct .txt: ${txtFound ?: "ไม่พบ"}\n" +
//                    "• Hash 32: ${hashFound ?: "ไม่พบ"}\n" +
//                    "• HTML Size: ${finalHtml.length} ตัวอักษร"
//        )
//    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(url = data, headers = defaultHeaders).document

        // ค้นหา Hash จากหน้าเว็บ
        val finalHtml = document.html()
        val hashMatch = Regex("""[a-fA-F0-9]{32}""").find(finalHtml)?.value ?: "test_hash_not_found"

        val testUrl = "https://player-ok-goal.doodee-player.com/m3u8/$hashMatch-720.txt"

        // พ่น URL ออกมาดูบนหน้าจอทันทีเพื่อตรวจสอบ
        throw ErrorLoadingException("Generated URL: $testUrl")
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