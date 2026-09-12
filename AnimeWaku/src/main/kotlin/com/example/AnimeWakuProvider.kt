package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder
import java.net.URI
import org.jsoup.Jsoup

class AnimeWakuProvider : MainAPI() {

    override var mainUrl = "https://anime-waku.com"
    override var name = "AnimeWaku"
    override val supportedTypes = setOf(TvType.Anime)
    override var lang = "th"
    override val hasMainPage = true

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/",
        "Accept-Language" to "th-TH,th;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/anime/?get=anime" to "อนิเมะอัปเดตล่าสุด"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page <= 1) {
            "$mainUrl/anime/?get=anime"
        } else {
            "$mainUrl/anime/page/$page/?get=anime"
        }

        return try {
            val results = parseAnimeLinks(app.get(url, headers = defaultHeaders).document)
            newHomePageResponse(request.name, results, hasNext = results.isNotEmpty())
        } catch (_: Exception) {
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }
    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val url = "$mainUrl/?s=${URLEncoder.encode(query, "UTF-8")}"
            parseAnimeLinks(app.get(url, headers = defaultHeaders).document)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseAnimeLinks(document: org.jsoup.nodes.Document): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val seen = hashSetOf<String>()
        document.select("a[href*='/anime/']").forEach { element ->
            val href = fixUrlNull(element.attr("href")) ?: return@forEach
            if (!seen.add(href)) return@forEach
            val title = element.text().trim().ifBlank {
                element.selectFirst("img")?.attr("alt")?.trim().orEmpty()
            }.replace(Regex("\\s+"), " ").trim()
            if (title.isBlank()) return@forEach
            val image = element.selectFirst("img")
            val poster = fixUrlNull(
                listOf("data-lazy-src", "data-src", "data-original", "src")
                    .asSequence().map { image?.attr(it).orEmpty() }
                    .firstOrNull { it.isNotBlank() }
            )
            results += newAnimeSearchResponse(title, href, TvType.Anime) { posterUrl = poster }
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = defaultHeaders).document
        val title = document.selectFirst("h1")?.text()?.trim() ?: "Unknown"
        val image = document.selectFirst(".poster img, .entry-content img, img")
        val poster = fixUrlNull(image?.attr("data-lazy-src")?.takeIf { it.isNotBlank() }
            ?: image?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: image?.attr("src"))
        val subEpisodes = mutableListOf<Episode>()
        val dubEpisodes = mutableListOf<Episode>()
        val seen = hashSetOf<String>()

        document.select("a[href*='/ep/']").forEach { element ->
            val href = fixUrlNull(element.attr("href")) ?: return@forEach
            if (!seen.add(href)) return@forEach
            val episodeName = element.text().trim().ifBlank { "ตอน" }
            val number = Regex("ตอนที่\\s*(\\d+)").find(episodeName)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("/ep/(\\d+)").find(href)?.groupValues?.get(1)?.toIntOrNull()
                ?: (subEpisodes.size + dubEpisodes.size + 1)
            val episode = newEpisode(href) {
                name = episodeName
                episode = number
            }
            if (episodeName.contains("พากย์ไทย", ignoreCase = true)) dubEpisodes += episode
            else subEpisodes += episode
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            posterUrl = poster
            if (subEpisodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEpisodes.sortedBy { it.episode })
            if (dubEpisodes.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEpisodes.sortedBy { it.episode })
        }
    }
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

        return try {

            // 1. Load episode page
            val document = app.get(
                data,
                headers = defaultHeaders
            ).document

            // 2. Find player options
            val options = document.select(
                "ul#playeroptionsul li, li.dooplay_player_option"
            )

            if (options.isEmpty()) {
                return false
            }

            var loaded = false
            val seenPlaylistUrls = hashSetOf<String>()

            // 3. Try every player
            for (option in options) {

                val postId = option
                    .attr("data-post")
                    .trim()

                val nume = option
                    .attr("data-nume")
                    .trim()

                val type = option
                    .attr("data-type")
                    .trim()

                if (postId.isBlank()) continue
                if (nume.isBlank()) continue

                try {

                    // 4. DooPlay AJAX
                    val response = app.post(
                        "$mainUrl/wp-admin/admin-ajax.php",
                        data = mapOf(
                            "action" to "doo_player_ajax",
                            "post" to postId,
                            "nume" to nume,
                            "type" to type
                        ),
                        headers = defaultHeaders + mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                            "Origin" to mainUrl,
                            "Referer" to data
                        )
                    )

                    val body = response.text.trim()

                    if (body.isBlank()) continue

                    // ------------------------------------------------
                    // 5. Parse embed_url from JSON
                    // ------------------------------------------------

                    var embedUrl: String? = null

                    try {

                        val json = org.json.JSONObject(body)

                        embedUrl =
                            json.optString("embed_url")
                                .takeIf { it.isNotBlank() }

                    } catch (_: Exception) {

                        // fallback: response อาจเป็น HTML
                        embedUrl =
                            Jsoup.parse(body)
                                .selectFirst("iframe")
                                ?.attr("src")
                                ?.trim()
                    }

                    if (embedUrl.isNullOrBlank()) {
                        continue
                    }

                    val wrapperUrl =
                        fixUrlNull(embedUrl)
                            ?: continue

                    // The hash is often inside a second iframe, not the AJAX wrapper.
                    val pages = loadPlayerPages(wrapperUrl, data)
                    pages.forEach { (html, pageUrl) ->
                        val normalized = html
                            .replace("\\/", "/")
                            .replace("\\u0026", "&")
                            .replace("&amp;", "&")

                        val directUrls = linkedSetOf<String>()
                        Regex("""https?://[^"'<>\\\s]+(?:\.m3u8|\.txt|\.mp4)(?:\?[^"'<>\\\s]*)?""", RegexOption.IGNORE_CASE)
                            .findAll(normalized)
                            .forEach { directUrls += it.value }
                        Regex("""["']file["']\s*:\s*["']([^"']+)["']""")
                            .findAll(normalized)
                            .forEach { directUrls += it.groupValues[1] }

                        directUrls.forEach { rawUrl ->
                            val videoUrl = fixUrlNull(rawUrl) ?: return@forEach
                            if (!seenPlaylistUrls.add(videoUrl)) return@forEach
                            val linkType = if (videoUrl.contains(".m3u8") || videoUrl.contains(".txt")) {
                                ExtractorLinkType.M3U8
                            } else {
                                ExtractorLinkType.VIDEO
                            }
                            callback.invoke(newExtractorLink(name, "$name Player $nume", videoUrl, linkType) {
                                quality = Qualities.P720.value
                                referer = pageUrl
                            })
                            loaded = true
                        }

                        Regex("""(?<![a-fA-F0-9])[a-fA-F0-9]{32}(?![a-fA-F0-9])""")
                            .find(normalized)?.value?.let { hash ->
                                listOf("1080", "720", "480", "360").forEach { quality ->
                                    val playlistUrl = "https://player-ok-goal.doodee-player.com/m3u8/$hash-$quality.txt"
                                    if (!seenPlaylistUrls.add(playlistUrl)) return@forEach
                                    callback.invoke(newExtractorLink(name, "$name Player $nume ($quality)", playlistUrl, ExtractorLinkType.M3U8) {
                                        this.quality = quality.toInt()
                                        referer = pageUrl
                                    })
                                    loaded = true
                                }
                            }
                    }

                } catch (_: Exception) {
                    continue
                }
            }

            loaded

        } catch (_: Exception) {

            false
        }
    }

    private suspend fun loadPlayerPages(
        firstUrl: String,
        episodeUrl: String
    ): List<Pair<String, String>> {
        val pages = mutableListOf<Pair<String, String>>()
        val visited = hashSetOf<String>()
        var nextUrl: String? = firstUrl
        var referer = episodeUrl

        repeat(4) {
            val url = nextUrl ?: return@repeat
            if (!visited.add(url)) return@repeat

            val response = app.get(url, headers = defaultHeaders, referer = referer)
            pages += response.text to url

            val iframe = response.document
                .selectFirst("iframe#embedvideo, iframe[src], iframe[data-src]")
                ?.let { it.attr("src").ifBlank { it.attr("data-src") } }

            nextUrl = iframe?.let { raw ->
                runCatching { URI(url).resolve(raw).toString() }.getOrNull()
                    ?: fixUrlNull(raw)
            }
            referer = url
        }
        return pages
    }

}