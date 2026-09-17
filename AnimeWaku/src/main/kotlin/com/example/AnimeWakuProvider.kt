package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.network.WebViewResolver
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

    private fun isBlockedPlayerUrl(url: String): Boolean {
        val normalized = url.lowercase()
        return normalized.contains("cloudflare") ||
                normalized.contains("challenge") ||
                normalized.contains("captcha") ||
                normalized.contains("cf-challenge") ||
                normalized.contains("access denied") ||
                normalized.contains("verification")
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        return try {
            val cloudflareKiller = CloudflareKiller()
            val document = app.get(
                data,
                headers = defaultHeaders,
                interceptor = cloudflareKiller
            ).document
            val options = document.select("ul#playeroptionsul li, li.dooplay_player_option")
            val directPlayerUrls = scrapePlayerUrls(document, data)

            var loaded = false
            for (playerUrl in directPlayerUrls) {
                if (loadExtractor(playerUrl, data, subtitleCallback, callback)) {
                    loaded = true
                    break
                }
            }

            if (options.isEmpty()) {
                if (loaded) return true

                directPlayerUrls
                    .filter { url ->
                        url.contains(".m3u8", ignoreCase = true) ||
                            url.contains(".txt", ignoreCase = true) ||
                            url.contains(".mp4", ignoreCase = true)
                    }
                    .forEach { mediaUrl ->
                        val linkType = if (mediaUrl.contains(".m3u8", ignoreCase = true) ||
                            mediaUrl.contains(".txt", ignoreCase = true)
                        ) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

                        callback(newExtractorLink(name, "$name Scraped Player", mediaUrl, linkType) {
                            quality = Qualities.P720.value
                            referer = data
                            headers = mapOf(
                                "User-Agent" to defaultHeaders["User-Agent"].orEmpty(),
                                "Referer" to data
                            )
                        })
                        loaded = true
                    }

                if (loaded) return true

                val resolver = WebViewResolver(
                    interceptUrl = Regex("""(?i)\.(m3u8|mp4|txt)(?:\?|$)"""),
                    additionalUrls = listOf(Regex("""(?i)\.(m3u8|mp4|txt)(?:\?|$)""")),
                    script = """
                        document.querySelector('video,button,[role="button"],.jw-icon-display,.vjs-big-play-button,.vds-play-button')?.click();
                    """.trimIndent(),
                    useOkhttp = false,
                    timeout = 30_000L
                )

                for (playerUrl in directPlayerUrls) {
                    val resolved = runCatching {
                        app.get(playerUrl, referer = data, interceptor = resolver).url
                    }.getOrNull() ?: continue
                    if (isBlockedPlayerUrl(resolved)) continue
                    if (!resolved.contains(".m3u8", ignoreCase = true) &&
                        !resolved.contains(".mp4", ignoreCase = true) &&
                        !resolved.contains(".txt", ignoreCase = true)
                    ) continue

                    val linkType = if (resolved.contains(".m3u8", ignoreCase = true) ||
                        resolved.contains(".txt", ignoreCase = true)
                    ) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

                    callback(newExtractorLink(name, "$name Direct Player", resolved, linkType) {
                        quality = Qualities.P720.value
                        referer = playerUrl
                        headers = mapOf(
                            "User-Agent" to defaultHeaders["User-Agent"].orEmpty(),
                            "Referer" to playerUrl
                        )
                    })
                    loaded = true
                    break
                }

                return loaded
            }

            val orderedOptions = options.sortedBy { option ->
                val nume = option.attr("data-nume").trim()
                if (nume == "3") 0 else if (nume.isNotBlank()) 1 else 99
            }

            val seenPlaylistUrls = hashSetOf<String>()

            for (option in orderedOptions) {
                val postId = option.attr("data-post").trim()
                val nume = option.attr("data-nume").trim()
                val type = option.attr("data-type").trim().ifBlank { "tv" }

                if (postId.isBlank() || nume.isBlank()) continue

                try {
                    val apiUrl = "$mainUrl/wp-json/dooplayer/v1/post/$postId?type=$type&source=$nume"
                    val restBody = runCatching {
                        app.get(
                            apiUrl,
                            headers = defaultHeaders + mapOf(
                                "Accept" to "application/json",
                                "Referer" to data
                            ),
                            interceptor = cloudflareKiller
                        ).text.trim()
                    }.getOrDefault("")

                    val embedUrl = extractEmbedUrl(restBody)
                        ?: runCatching {
                            app.post(
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
                                ),
                                interceptor = cloudflareKiller
                            ).text.trim()
                        }.getOrNull()?.let(::extractEmbedUrl)
                        ?: continue

                    val wrapperUrl = fixUrlNull(embedUrl) ?: continue
                    val extractorUrl = if (wrapperUrl.contains("ok.ru/videoembed/")) {
                        wrapperUrl.replace("/videoembed/", "/video/")
                    } else {
                        wrapperUrl
                    }

                    val pages = loadPlayerPages(wrapperUrl, data, cloudflareKiller)

                    val extractorCandidates = listOf(extractorUrl, wrapperUrl).distinct()
                    for (candidateUrl in extractorCandidates) {
                        if (loadExtractor(candidateUrl, "$mainUrl/", subtitleCallback, callback)) {
                            loaded = true
                            break
                        }
                    }
                    if (loaded) continue

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
                            if (isBlockedPlayerUrl(videoUrl) || !seenPlaylistUrls.add(videoUrl)) return@forEach

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
                    }

                    if (!loaded) {
                        val candidates = (listOf(extractorUrl, wrapperUrl) + pages.map { it.second }).distinct()
                        for (candidateUrl in candidates) {
                            if (loadExtractor(candidateUrl, data, subtitleCallback, callback)) {
                                loaded = true
                                break
                            }
                        }
                    }

                    if (!loaded) {
                        val resolver = WebViewResolver(
                            interceptUrl = Regex("""(?i)\.(m3u8|mp4|txt)(?:\?|$)"""),
                            additionalUrls = listOf(Regex("""(?i)\.(m3u8|mp4|txt)(?:\?|$)""")),
                            script = """
                                document.querySelector('video,button,[role="button"],.jw-icon-display,.vjs-big-play-button,.vds-play-button')?.click();
                            """.trimIndent(),
                            useOkhttp = false,
                            timeout = 30_000L
                        )

                        val candidates = (listOf(wrapperUrl) + pages.map { it.second }).distinct()
                        for (candidateUrl in candidates) {
                            val resolved = app.get(candidateUrl, referer = data, interceptor = resolver).url
                            if (isBlockedPlayerUrl(resolved)) continue
                            if (!resolved.contains(".m3u8", ignoreCase = true) &&
                                !resolved.contains(".mp4", ignoreCase = true)
                            ) continue

                            val linkType = if (resolved.contains(".m3u8", ignoreCase = true)) {
                                ExtractorLinkType.M3U8
                            } else {
                                ExtractorLinkType.VIDEO
                            }

                            callback.invoke(newExtractorLink(
                                name,
                                "$name Player $nume",
                                resolved,
                                linkType
                            ) {
                                quality = Qualities.P720.value
                                referer = candidateUrl
                                headers = mapOf(
                                    "User-Agent" to defaultHeaders["User-Agent"].orEmpty(),
                                    "Referer" to candidateUrl
                                )
                            })
                            loaded = true
                            break
                        }
                    }
                } catch (_: Exception) {
                    continue
                }
            }

            if (!loaded) {
                val episodeResolver = WebViewResolver(
                    interceptUrl = Regex("""(?i)\.(m3u8|mp4|txt)(?:[?#]|$)"""),
                    additionalUrls = listOf(Regex("""(?i)\.(m3u8|mp4|txt)(?:[?#]|$)""")),
                    script = """
                        (function () {
                            var clicked = false;
                            function clickPlayer() {
                                var option = document.querySelector(
                                    '#playeroptionsul li, li.dooplay_player_option, [data-nume], [data-post]'
                                );
                                if (option && !clicked) {
                                    clicked = true;
                                    option.click();
                                }

                                var button = document.querySelector(
                                    'video, button, [role="button"], .jw-icon-display, .vjs-big-play-button, .vds-play-button'
                                );
                                if (button) button.click();
                            }

                            clickPlayer();
                            var observer = new MutationObserver(clickPlayer);
                            observer.observe(document.documentElement, { childList: true, subtree: true });
                            setInterval(clickPlayer, 1000);
                        })();
                    """.trimIndent(),
                    useOkhttp = false,
                    timeout = 120_000L
                )

                val resolved = runCatching {
                    app.get(data, referer = mainUrl, interceptor = episodeResolver).url
                }.getOrNull()

                if (!resolved.isNullOrBlank() && !isBlockedPlayerUrl(resolved) &&
                    (resolved.contains(".m3u8", ignoreCase = true) ||
                        resolved.contains(".mp4", ignoreCase = true) ||
                        resolved.contains(".txt", ignoreCase = true))
                ) {
                    val linkType = if (resolved.contains(".m3u8", ignoreCase = true) ||
                        resolved.contains(".txt", ignoreCase = true)
                    ) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

                    callback(newExtractorLink(name, "$name Episode WebView", resolved, linkType) {
                        quality = Qualities.P720.value
                        referer = data
                        headers = mapOf(
                            "User-Agent" to defaultHeaders["User-Agent"].orEmpty(),
                            "Referer" to data
                        )
                    })
                    loaded = true
                }
            }

            loaded
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun loadPlayerPages(
        firstUrl: String,
        episodeUrl: String,
        cloudflareKiller: CloudflareKiller
    ): List<Pair<String, String>> {
        val pages = mutableListOf<Pair<String, String>>()
        val visited = hashSetOf<String>()
        var nextUrl: String? = firstUrl
        var referer = episodeUrl

        repeat(4) {
            val url = nextUrl ?: return@repeat
            if (!visited.add(url)) return@repeat

            val response = try {
                app.get(
                    url,
                    headers = defaultHeaders,
                    referer = referer,
                    interceptor = cloudflareKiller
                )
            } catch (_: Exception) {
                // Keep the URL so the registered CloudStream extractor/WebView            s
                // can still resolve an iframe that rejects a plain HTTP request. test commit 31
                pages += "" to url
                return@repeat
            }
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

    private fun extractEmbedUrl(body: String): String? {
        if (body.isBlank()) return null

        val json = runCatching { org.json.JSONObject(body) }.getOrNull()
        if (json != null) {
            val urlKeys = listOf("embed_url", "embed", "url", "source", "link")
            for (key in urlKeys) {
                json.optString(key).trim().takeIf { it.isNotBlank() }?.let { return it }
            }
            for (key in listOf("data", "result", "player")) {
                val nested = json.optJSONObject(key) ?: continue
                for (urlKey in urlKeys) {
                    nested.optString(urlKey).trim().takeIf { it.isNotBlank() }?.let { return it }
                }
            }
        }

        return Jsoup.parse(body)
            .selectFirst("iframe[src], iframe[data-src]")
            ?.let { it.attr("src").ifBlank { it.attr("data-src") } }
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun scrapePlayerUrls(document: org.jsoup.nodes.Document, pageUrl: String): List<String> {
        val candidates = linkedSetOf<String>()
        val playerAttributes = listOf("src", "data-src", "data-url", "data-embed", "data-player", "data-link")

        document.select(
            "iframe, embed, video, source, [data-player], [data-embed], [data-src], [data-url], [data-link]"
        ).forEach { element ->
            playerAttributes.forEach { attribute ->
                val rawUrl = element.attr(attribute).trim()
                resolvePlayerUrl(rawUrl, pageUrl)?.let { candidates += it }
            }
        }

        val normalizedHtml = document.html()
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("\\u0026", "&")

        Regex("""(?:https?:)?//[^\"'<>\\\s]+""", RegexOption.IGNORE_CASE)
            .findAll(normalizedHtml)
            .map { it.value }
            .mapNotNull { resolvePlayerUrl(it, pageUrl) }
            .filter { url ->
                url.contains("m3u8", ignoreCase = true) ||
                    url.contains(".mp4", ignoreCase = true) ||
                    url.contains(".txt", ignoreCase = true) ||
                    url.contains("embed", ignoreCase = true) ||
                    url.contains("player", ignoreCase = true) ||
                    url.contains("stream", ignoreCase = true)
            }
            .forEach { candidates += it }

        return candidates.filterNot(::isBlockedPlayerUrl)
    }

    private fun resolvePlayerUrl(rawUrl: String, pageUrl: String): String? {
        if (rawUrl.isBlank() || rawUrl.startsWith("javascript:", ignoreCase = true)) return null
        val cleanedUrl = rawUrl.trim('"', '\'', '`')
        return runCatching { URI(pageUrl).resolve(cleanedUrl).toString() }.getOrNull()
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: fixUrlNull(cleanedUrl)
    }

}