package com.example

import com.lagradost.api.Log
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

    private val searchHeaders = defaultHeaders + mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "same-origin"
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
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val apiUrl = "$mainUrl/wp-json/wp/v2/search?search=$encodedQuery&per_page=30"
            val apiResults = try {
                val response = app.get(
                    apiUrl,
                    headers = defaultHeaders + mapOf("Accept" to "application/json")
                )
                Log.d("AnimeWakuSearch", "REST url=$apiUrl code=${response.code} length=${response.text.length} body=${response.text.take(200)}")
                parseWordPressSearch(response.text)
            } catch (error: Exception) {
                Log.w("AnimeWakuSearch", "REST failed error=${error.message}")
                emptyList()
            }

            Log.d("AnimeWakuSearch", "REST results=${apiResults.size}")
            if (apiResults.isNotEmpty()) return apiResults

            val animeApiResults = try {
                val animeApiUrl = "$mainUrl/wp-json/wp/v2/anime?search=$encodedQuery&per_page=30"
                val response = app.get(
                    animeApiUrl,
                    headers = defaultHeaders + mapOf("Accept" to "application/json")
                )
                val results = parseWordPressPosts(response.text)
                Log.d("AnimeWakuSearch", "Anime REST url=$animeApiUrl code=${response.code} length=${response.text.length} results=${results.size}")
                results
            } catch (error: Exception) {
                Log.w("AnimeWakuSearch", "Anime REST failed error=${error.message}")
                emptyList()
            }
            if (animeApiResults.isNotEmpty()) return animeApiResults

            val catalogUrl = "$mainUrl/anime/?get=anime&s=$encodedQuery"
            val catalogResults = try {
                val response = app.get(catalogUrl, headers = searchHeaders)
                val results = parseAnimeLinks(response.document)
                Log.d("AnimeWakuSearch", "Catalog url=$catalogUrl code=${response.code} length=${response.text.length} results=${results.size}")
                results
            } catch (error: Exception) {
                Log.w("AnimeWakuSearch", "Catalog failed error=${error.message}")
                emptyList()
            }

            val fallbackResults = mutableListOf<SearchResponse>()
            val seenFallback = hashSetOf<String>().apply {
                catalogResults.mapTo(this) { it.url }
            }
            fallbackResults += catalogResults
            var emptyPages = 0
            for (page in 1..67) {
                val catalogFallbackUrl = if (page == 1) "$mainUrl/anime/?get=anime"
                else "$mainUrl/anime/page/$page/?get=anime"
                val fallbackResponse = app.get(catalogFallbackUrl, headers = searchHeaders)
                val pageResults = parseAnimeLinks(fallbackResponse.document)
                    .filter { it.name.contains(query, ignoreCase = true) }
                    .filter { seenFallback.add(it.url) }
                fallbackResults += pageResults
                Log.d("AnimeWakuSearch", "Local fallback page=$page code=${fallbackResponse.code} pageMatches=${pageResults.size} total=${fallbackResults.size} title=${fallbackResponse.document.title()}")
                if (pageResults.isEmpty()) emptyPages++ else emptyPages = 0
                if (emptyPages >= 2 && page >= 3) break
            }
            fallbackResults
        } catch (error: Exception) {
            Log.e("AnimeWakuSearch", "Search failed query=$query error=${error.message}")
            emptyList()
        }
    }

    private suspend fun parseWordPressSearch(body: String): List<SearchResponse> {
        val json = runCatching { org.json.JSONArray(body) }.getOrNull() ?: return emptyList()
        val results = mutableListOf<SearchResponse>()
        val seen = hashSetOf<String>()

        for (index in 0 until json.length()) {
            val item = json.optJSONObject(index) ?: continue
            val url = fixUrlNull(item.optString("url")) ?: continue
            if (!url.contains("/anime/", ignoreCase = true) || !seen.add(url)) {
                Log.d("AnimeWakuSearch", "REST skip index=$index url=$url")
                continue
            }
            val title = Jsoup.parse(item.optString("title")).text().trim()
            if (title.isBlank()) {
                Log.d("AnimeWakuSearch", "REST skip index=$index reason=blank-title url=$url")
                continue
            }
            val poster = findPoster(url)
            Log.d("AnimeWakuSearch", "REST item title=$title url=$url poster=${poster != null}")
            results += newAnimeSearchResponse(title, url, TvType.Anime) {
                posterUrl = poster
            }
        }
        return results
    }

    private suspend fun parseWordPressPosts(body: String): List<SearchResponse> {
        val json = runCatching { org.json.JSONArray(body) }.getOrNull() ?: return emptyList()
        val results = mutableListOf<SearchResponse>()
        val seen = hashSetOf<String>()
        for (index in 0 until json.length()) {
            val item = json.optJSONObject(index) ?: continue
            val url = fixUrlNull(item.optString("link")) ?: continue
            if (!url.contains("/anime/", ignoreCase = true) || !seen.add(url)) continue
            val titleObject = item.optJSONObject("title")
            val title = Jsoup.parse(titleObject?.optString("rendered").orEmpty()).text().trim()
            if (title.isBlank()) continue
            val poster = findPoster(url)
            Log.d("AnimeWakuSearch", "Anime REST item title=$title url=$url poster=${poster != null}")
            results += newAnimeSearchResponse(title, url, TvType.Anime) { posterUrl = poster }
        }
        return results
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
            val poster = element.closest("article, .item, .post")?.let(::findPoster)
            results += newAnimeSearchResponse(title, href, TvType.Anime) { posterUrl = poster }
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = defaultHeaders).document
        val title = document.selectFirst("h1")?.text()?.trim() ?: "Unknown"
        val poster = findPoster(url, document)
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

    private suspend fun findPoster(url: String, document: org.jsoup.nodes.Document? = null): String? {
        val doc = document ?: runCatching { app.get(url, headers = defaultHeaders).document }.getOrNull()
            ?: return null
        val image = doc.select(
            """
            .poster img, .thumb img, .thumbnail img, .poster, 
            article img, .item img, .post img, 
            meta[property=og:image], meta[name=twitter:image]
            """.trimIndent()
        ).mapNotNull { element ->
            val raw = if (element.tagName() == "meta") element.attr("content")
            else listOf("data-lazy-src", "data-src", "data-original", "data-bg", "data-background-image", "src")
                .asSequence().map { element.attr(it) }.firstOrNull { it.isNotBlank() }.orEmpty()
            extractImageUrl(raw)?.takeUnless(::isBadPoster)
        }.firstOrNull()
        return image
    }

    private fun findPoster(element: org.jsoup.nodes.Element): String? {
        val image = element.select("img, meta[property=og:image], meta[name=twitter:image]")
            .mapNotNull { imageElement ->
                val raw = if (imageElement.tagName() == "meta") imageElement.attr("content")
                else listOf("data-lazy-src", "data-src", "data-original", "data-bg", "data-background-image", "src")
                    .asSequence().map { imageElement.attr(it) }.firstOrNull { it.isNotBlank() }.orEmpty()
                extractImageUrl(raw)?.takeUnless(::isBadPoster)
            }.firstOrNull()
        return image
    }

    private fun extractImageUrl(raw: String): String? {
        val value = raw.trim()
        if (value.isBlank() || value.startsWith("data:", ignoreCase = true)) return null
        val background = Regex("""url\(['\"]?([^'\")]+)""").find(value)?.groupValues?.get(1)
        return fixUrlNull(background ?: value)
    }

    private fun isBadPoster(url: String): Boolean {
        val value = url.lowercase()
        return value.contains("/ads/") || value.contains("/ad/") ||
            value.contains("banner") || value.contains("logo") ||
            value.contains("favicon") || value.contains("placeholder") ||
            value.contains("avatar") || value.contains("no-image") ||
            value.contains("default-image") || value.contains("lazyload") ||
            value.endsWith(".gif") || value.endsWith(".svg")
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
            val options = document.select(
                "ul#playeroptionsul li, li.dooplay_player_option, " +
                    "[data-post][data-nume], [data-post][data-source], [data-nume][data-type]"
            )
            val directPlayerUrls = scrapePlayerUrls(document, data)
            Log.d("AnimeWaku", "loadLinks page=${data.substringAfterLast('/')} options=${options.size} directUrls=${directPlayerUrls.size}")

            var loaded = false
            for (playerUrl in directPlayerUrls) {
                if (loadExtractor(playerUrl, data, subtitleCallback, callback)) {
                    loaded = true
                    break
                }
            }

            if (options.isEmpty()) {
                Log.w("AnimeWaku", "No DooPlayer options found for $data")
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
                val nume = option.attr("data-nume").trim().ifBlank { option.attr("data-source").trim() }
                val type = option.attr("data-type").trim().ifBlank { "tv" }

                if (postId.isBlank() || nume.isBlank()) {
                    Log.w("AnimeWaku", "Skipping player option with missing post/source")
                    continue
                }

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
                    Log.d("AnimeWaku", "REST player post=$postId source=$nume responseLength=${restBody.length}")
                    Log.d("AnimeWaku", "REST body=${restBody.take(300)}")

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
                        }.getOrNull()?.also {
                            Log.d("AnimeWaku", "AJAX player post=$postId responseLength=${it.length}")
                        }?.let(::extractEmbedUrl)
                        ?: continue

                    val wrapperUrl = fixUrlNull(embedUrl) ?: continue
                    Log.d("AnimeWaku", "Embed URL=${wrapperUrl.take(300)}")
                    val extractorUrl = if (wrapperUrl.contains("ok.ru/videoembed/")) {
                        wrapperUrl.replace("/videoembed/", "/video/")
                    } else {
                        wrapperUrl
                    }

                    val pages = loadPlayerPages(wrapperUrl, data, cloudflareKiller)
                    val pageCandidates = pages.map { it.second }
                    Log.d("AnimeWaku", "Player pages=${pages.size} urls=${pageCandidates.joinToString().take(500)}")

                    val extractorCandidates = listOf(extractorUrl, wrapperUrl).distinct()
                    for (candidateUrl in extractorCandidates) {
                        var emittedLinks = 0
                        loadExtractor(candidateUrl, "$mainUrl/", subtitleCallback) { link ->
                            emittedLinks++
                            callback(link)
                        }
                        if (emittedLinks > 0) {
                            Log.d("AnimeWaku", "Extractor loaded source=$nume url=${candidateUrl.take(300)}")
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

                        val directUrls = extractMediaUrls(normalized, pageUrl)

                        directUrls.forEach { rawUrl ->
                            val videoUrl = fixUrlNull(rawUrl) ?: return@forEach
                            if (isBlockedPlayerUrl(videoUrl) || !seenPlaylistUrls.add(videoUrl)) return@forEach

                            val linkType = mediaLinkType(videoUrl)

                            callback.invoke(newExtractorLink(name, playerLabel(nume, videoUrl), videoUrl, linkType) {
                                quality = Qualities.P720.value
                                referer = mediaReferer(videoUrl, pageUrl)
                            })
                            Log.d("AnimeWaku", "Direct media source=$nume type=$linkType url=${videoUrl.take(300)} referer=${mediaReferer(videoUrl, pageUrl)}")
                            loaded = true
                        }
                    }

                    if (!loaded) {
                        val candidates = (listOf(extractorUrl, wrapperUrl) + pageCandidates).distinct()
                        for (candidateUrl in candidates) {
                            var emittedLinks = 0
                            loadExtractor(candidateUrl, data, subtitleCallback) { link ->
                                emittedLinks++
                                callback(link)
                            }
                            if (emittedLinks > 0) {
                                Log.d("AnimeWaku", "Nested extractor loaded source=$nume url=${candidateUrl.take(300)}")
                                loaded = true
                                break
                            }
                        }
                    }

                    val playerHasChallenge = pages.any { (html, _) ->
                        html.contains("cloudflare", ignoreCase = true) ||
                            html.contains("challenge", ignoreCase = true) ||
                            html.contains("just a moment", ignoreCase = true)
                    }
                    Log.d("AnimeWaku", "Player challenge source=$nume detected=$playerHasChallenge pages=${pages.size}")

                    if (!loaded && !playerHasChallenge) {
                        val resolver = WebViewResolver(
                            interceptUrl = mediaRequestRegex,
                            additionalUrls = listOf(mediaRequestRegex),
                            script = playerResolverScript,
                            useOkhttp = false,
                            userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36",
                            timeout = 20_000L
                        )

                        val candidates = (listOf(wrapperUrl) + pageCandidates).distinct()
                        for (candidateUrl in candidates) {
                            val resolved = runCatching {
                                app.get(candidateUrl, referer = data, interceptor = resolver).url
                            }.onFailure { error ->
                                Log.w("AnimeWaku", "WebView failed url=${candidateUrl.take(200)} error=${error.message}")
                            }.getOrNull() ?: continue
                            if (isBlockedPlayerUrl(resolved)) continue
                            if (isUnsupportedImageHls(resolved)) {
                                Log.w("AnimeWaku", "Unsupported custom image HLS source=$nume url=${resolved.take(300)}")
                                continue
                            }
                            if (!isLikelyMediaUrl(resolved)) continue

                            val linkType = mediaLinkType(resolved)

                            callback.invoke(newExtractorLink(
                                name,
                                playerLabel(nume, resolved),
                                resolved,
                                linkType
                            ) {
                                quality = Qualities.P720.value
                                referer = mediaReferer(resolved, candidateUrl)
                                headers = mapOf(
                                    "User-Agent" to defaultHeaders["User-Agent"].orEmpty(),
                                    "Referer" to mediaReferer(resolved, candidateUrl),
                                    "Origin" to mediaOrigin(resolved, candidateUrl)
                                )
                            })
                            Log.d("AnimeWaku", "WebView media source=$nume type=$linkType url=${resolved.take(300)} referer=${mediaReferer(resolved, candidateUrl)}")
                            loaded = true
                            break
                        }
                    }

                    if (!loaded) {
                        val secondPlayerResolver = WebViewResolver(
                            interceptUrl = secondPlayerUrlRegex,
                            additionalUrls = listOf(secondPlayerUrlRegex),
                            script = secondPlayerResolverScript,
                            useOkhttp = false,
                            userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36",
                            timeout = 30_000L
                        )

                        for (candidateUrl in listOf(wrapperUrl).distinct()) {
                            val resolved = runCatching {
                                app.get(candidateUrl, referer = data, interceptor = secondPlayerResolver).url
                            }.onFailure { error ->
                                Log.w("AnimeWaku", "Player 2 WebView failed url=${candidateUrl.take(200)} error=${error.message}")
                            }.getOrNull() ?: continue
                            if (isBlockedPlayerUrl(resolved) || isUnsupportedImageHls(resolved)) continue

                            if (resolved.contains("doodee-player.com", ignoreCase = true)) {
                                Log.d("AnimeWaku", "Player 2 iframe discovered source=$nume url=${resolved.take(300)}")
                                if (loadExtractor(resolved, candidateUrl, subtitleCallback, callback)) {
                                    Log.d("AnimeWaku", "Player 2 extractor loaded source=$nume")
                                    loaded = true
                                    break
                                }

                                val doodeeResolver = WebViewResolver(
                                    interceptUrl = doodeeMediaRegex,
                                    additionalUrls = listOf(doodeeMediaRegex),
                                    script = doodeePlayerScript,
                                    useOkhttp = false,
                                    userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36",
                                    timeout = 120_000L
                                )
                                val mediaUrl = runCatching {
                                    app.get(resolved, referer = candidateUrl, interceptor = doodeeResolver).url
                                }.onFailure { error ->
                                    Log.w("AnimeWaku", "Player 2 media WebView failed error=${error.message}")
                                }.getOrNull()

                                if (!mediaUrl.isNullOrBlank() && !isBlockedPlayerUrl(mediaUrl) &&
                                    doodeeMediaRegex.containsMatchIn(mediaUrl)
                                ) {
                                    callback.invoke(newExtractorLink(name, "${name} Player 2 (OK.ru)", mediaUrl, ExtractorLinkType.M3U8) {
                                        quality = Qualities.P720.value
                                        referer = "https://private-okru.doodee-player.com/"
                                        headers = mapOf(
                                            "User-Agent" to defaultHeaders["User-Agent"].orEmpty(),
                                            "Referer" to "https://private-okru.doodee-player.com/"
                                        )
                                    })
                                    Log.d("AnimeWaku", "Player 2 HLS source=$nume url=${mediaUrl.take(300)}")
                                    loaded = true
                                    break
                                }
                                continue
                            }
                            if (!isLikelyMediaUrl(resolved)) continue

                            val linkType = mediaLinkType(resolved)
                            callback.invoke(newExtractorLink(name, playerLabel(nume, resolved), resolved, linkType) {
                                quality = Qualities.P720.value
                                referer = mediaReferer(resolved, candidateUrl)
                                headers = mapOf(
                                    "User-Agent" to defaultHeaders["User-Agent"].orEmpty(),
                                    "Referer" to mediaReferer(resolved, candidateUrl),
                                    "Origin" to mediaOrigin(resolved, candidateUrl)
                                )
                            })
                            Log.d("AnimeWaku", "Player 2 media source=$nume type=$linkType url=${resolved.take(300)}")
                            loaded = true
                            break
                        }
                        if (!loaded) {
                            Log.w("AnimeWaku", "Player 2 unavailable source=$nume challenge=$playerHasChallenge")
                        }
                    }
                } catch (error: Exception) {
                    Log.w("AnimeWaku", "Player source failed post=$postId source=$nume: ${error.message}")
                    continue
                }
            }

            if (!loaded) {
                Log.w("AnimeWaku", "No playable link found after REST/AJAX/extractor/WebView attempts for $data")
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
                        }.onFailure { error ->
                            Log.w("AnimeWaku", "Episode WebView failed error=${error.message}")
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
        } catch (error: Exception) {
            Log.e("AnimeWaku", "loadLinks failed for $data: ${error.message}")
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
        val pendingUrls = mutableListOf(firstUrl)
        var pendingIndex = 0

        while (pendingIndex < pendingUrls.size && pendingIndex < 8) {
            val url = pendingUrls[pendingIndex++]
            if (!visited.add(url)) continue

            val response = try {
                app.get(
                    url,
                    headers = defaultHeaders,
                    referer = if (url == firstUrl) episodeUrl else firstUrl,
                    interceptor = cloudflareKiller
                )
            } catch (_: Exception) {
                // Keep the URL so the registered CloudStream extractor/WebView            s
                // can still resolve an iframe that rejects a plain HTTP request. test commit 31
                pages += "" to url
                continue
            }
            pages += response.text to url
            val pageText = response.text
            val markers = Regex(
                """(?i)(cloudflare|turnstile|captcha|challenge|verification|iframe|m3u8|mp4|source|video|fetch\s*\()"""
            ).findAll(pageText)
                .map { it.value.lowercase() }
                .distinct()
                .joinToString(",")
            val scriptUrls = response.document.select("script[src]")
                .map { it.attr("src") }
                .filter { it.isNotBlank() }
                .joinToString(",")
                .take(500)
            val frameUrls = response.document.select("iframe[src], iframe[data-src]")
                .map { it.attr("src").ifBlank { it.attr("data-src") } }
                .filter { it.isNotBlank() }
                .joinToString(",")
                .take(500)
            Log.d(
                "AnimeWaku",
                "Player HTML url=${url.take(180)} length=${pageText.length} " +
                    "title=${response.document.title().take(80)} " +
                    "markers=$markers scripts=$scriptUrls frames=$frameUrls"
            )

            response.document
                .select("iframe#embedvideo, iframe[src], iframe[data-src]")
                .map { it.attr("src").ifBlank { it.attr("data-src") } }
                .mapNotNull { raw ->
                    runCatching { URI(url).resolve(raw).toString() }.getOrNull()
                        ?: fixUrlNull(raw)
                }
                .filter { it.startsWith("http://") || it.startsWith("https://") }
                .filterNot(visited::contains)
                .forEach { pendingUrls += it }

            Regex(
                """https?://[^\"'<>\\\s]*doodee-player\.com[^\"'<>\\\s]*""",
                RegexOption.IGNORE_CASE
            ).findAll(pageText)
                .map { it.value.replace("\\/", "/") }
                .filterNot(visited::contains)
                .forEach { pendingUrls += it }
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

    private fun extractMediaUrls(html: String, pageUrl: String): Set<String> {
        val urls = linkedSetOf<String>()
        val mediaPattern = Regex(
            """(?:https?:)?//[^\"'<>\\\s]+|(?:/|\./|\.\./)[^\"'<>\\\s]+""",
            RegexOption.IGNORE_CASE
        )
        val mediaAttributes = Regex(
            """(?:file|src|source|hls|playlist|contentUrl)\s*[=:]\s*[\"']([^\"']+)[\"']""",
            RegexOption.IGNORE_CASE
        )

        (mediaPattern.findAll(html).map { it.value } + mediaAttributes.findAll(html).map { it.groupValues[1] })
            .mapNotNull { resolvePlayerUrl(it, pageUrl) }
            .filter(::isLikelyMediaUrl)
            .forEach { urls += it }

        return urls
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
                    url.contains("playervk", ignoreCase = true) ||
                    url.contains("stream", ignoreCase = true)
            }
            .forEach { candidates += it }

        return candidates.filterNot(::isBlockedPlayerUrl)
    }

    private val playerResolverScript = """
        (function () {
            if (window.__animeWakuResolverStarted) return;
            window.__animeWakuResolverStarted = true;

            function activate(element) {
                if (!element) return;
                try { element.click(); } catch (ignored) {}
                try {
                    if (element.play) element.play().catch(function () {});
                } catch (ignored) {}
            }

            function activatePlayers(root) {
                var selectors = [
                    'video', 'audio', 'button', '[role="button"]',
                    '.jw-icon-display', '.jw-video', '.vjs-big-play-button',
                    '.vjs-play-control', '.plyr__control--overlaid',
                    '.vds-play-button', '[onclick]',
                    'iframe[src]', 'iframe[data-src]'
                ];
                selectors.forEach(function (selector) {
                    try {
                        root.querySelectorAll(selector).forEach(activate);
                    } catch (ignored) {}
                });
            }

            function activateChallenge() {
                var challengeSelectors = [
                    '#challenge-stage button', '#challenge-stage input',
                    '#turnstile-wrapper button', '[name="cf-turnstile"]',
                    'iframe[title*="challenge"]', 'iframe[title*="verify"]'
                ];
                challengeSelectors.forEach(function (selector) {
                    try {
                        document.querySelectorAll(selector).forEach(activate);
                    } catch (ignored) {}
                });

                var title = (document.title || '').toLowerCase();
                var text = (document.body && document.body.innerText || '').toLowerCase();
                var waiting = title.indexOf('just a moment') >= 0 ||
                    text.indexOf('checking your browser') >= 0 ||
                    text.indexOf('verify you are human') >= 0 ||
                    text.indexOf('performing security verification') >= 0;
                if (waiting && !window.__animeWakuChallengeReloaded) {
                    window.__animeWakuChallengeReloaded = true;
                    setTimeout(function () { location.reload(); }, 7000);
                }
            }

            function mediaUrl(root) {
                var media = root.querySelector('video, audio');
                var sources = root.querySelectorAll('video source, audio source');
                var values = [];
                if (media) values.push(media.currentSrc, media.src);
                sources.forEach(function (source) { values.push(source.src, source.getAttribute('src')); });
                values.push(root.querySelector('[data-src]')?.getAttribute('data-src'));

                for (var i = 0; i < values.length; i++) {
                    var value = values[i];
                    if (value && (/\.(m3u8|mp4|txt)([?#]|$)/i.test(value) ||
                        /\/(stream|video|play|source|media)([\/?#]|$)/i.test(value) ||
                        /\/o\/[^\/?#]+\/v\/[^?#]+/i.test(value))) return value;
                }
                return null;
            }

            function inspectMedia() {
                activateChallenge();
                activatePlayers(document);
                var media = mediaUrl(document);
                if (media && media !== location.href) {
                    location.href = media;
                    return;
                }

                document.querySelectorAll('iframe[src], iframe[data-src]').forEach(function (frame) {
                    try {
                        var source = frame.src || frame.getAttribute('data-src');
                        if (!frame.src && source) frame.src = source;
                        if (source && source !== location.href) frame.contentWindow.postMessage('play', '*');
                    } catch (ignored) {}
                });
            }

            inspectMedia();
            new MutationObserver(inspectMedia).observe(document.documentElement, {
                childList: true,
                subtree: true,
                attributes: true
            });
            setInterval(inspectMedia, 1000);
        })();
    """.trimIndent()

    private val mediaRequestRegex = Regex(
        """(?i)(?:\.(m3u8|mp4|txt)(?:[?#]|$)|/(stream|video|play|source|media)(?:[/?#]|$)|/o/[^/?#]+/v/[^?#]+|(?:stream|video|play|source|media)=)"""
    )

    private val secondPlayerUrlRegex = Regex(
        """(?i)https?://[^\"'<>\\s]*doodee-player\.com[^\"'<>\\s]*"""
    )

    private val doodeeMediaRegex = Regex(
        """(?i)(?:player-ok-goal\.doodee-player\.com/(?:hls|m3u8)/|\.(?:m3u8|txt)(?:[?#]|$))"""
    )

    private val doodeePlayerScript = """
        (function () {
            function play() {
                document.querySelectorAll('video,button,[role="button"],.jw-icon-display,.jw-display-icon-container')
                    .forEach(function (element) {
                        try { element.click(); } catch (ignored) {}
                        try { if (element.play) element.play().catch(function () {}); } catch (ignored) {}
                    });
            }
            play();
            new MutationObserver(play).observe(document.documentElement, { childList: true, subtree: true });
            setInterval(play, 1000);
        })();
    """.trimIndent()

    private val secondPlayerResolverScript = """
        (function () {
            if (window.__animeWakuSecondPlayerStarted) return;
            window.__animeWakuSecondPlayerStarted = true;

            function clickSecondPlayer() {
                var second = document.querySelector(
                    '#list-server-more .list-server-items li:nth-child(2), .list-server-items li:nth-child(2)'
                );
                if (second) {
                    try { second.click(); } catch (ignored) {}
                }

                var frame = document.querySelector('iframe#embedvideo, iframe');
                var frameUrl = frame && (frame.src || frame.getAttribute('src') || '');
                if (frameUrl && /doodee-player\.com/i.test(frameUrl) && location.href !== frameUrl) {
                    location.href = frameUrl;
                    return;
                }

                document.querySelectorAll('video, audio, button, [role="button"], .jw-icon-display, .vjs-big-play-button')
                    .forEach(function (element) {
                        try { element.click(); } catch (ignored) {}
                        try { if (element.play) element.play().catch(function () {}); } catch (ignored) {}
                    });
            }

            clickSecondPlayer();
            new MutationObserver(clickSecondPlayer).observe(document.documentElement, {
                childList: true,
                subtree: true,
                attributes: true
            });
            setInterval(clickSecondPlayer, 1000);
        })();
    """.trimIndent()

    private fun isLikelyMediaUrl(url: String): Boolean {
        return url.contains(".m3u8", ignoreCase = true) ||
            url.contains(".mp4", ignoreCase = true) ||
            url.contains(".txt", ignoreCase = true) ||
            mediaRequestRegex.containsMatchIn(url)
    }

    private fun mediaLinkType(url: String): ExtractorLinkType {
        return if (url.contains(".m3u8", ignoreCase = true) ||
            url.contains(".txt", ignoreCase = true) ||
            url.contains("cat.animenani.com/o/", ignoreCase = true)
        ) {
            ExtractorLinkType.M3U8
        } else {
            ExtractorLinkType.VIDEO
        }
    }

    private fun isUnsupportedImageHls(url: String): Boolean {
        return url.contains("cat.animenani.com/o/", ignoreCase = true)
    }

    private fun playerLabel(source: String, url: String): String {
        return if (url.contains("doodee-player.com", ignoreCase = true)) {
            "$name Player 2 (OK.ru)"
        } else {
            "$name Player $source"
        }
    }

    private fun mediaReferer(mediaUrl: String, fallback: String): String {
        return when {
            mediaUrl.contains("cat.animenani.com", ignoreCase = true) -> "https://nya.animenani.com/"
            else -> fallback
        }
    }

    private fun mediaOrigin(mediaUrl: String, fallback: String): String {
        return runCatching {
            val uri = URI(mediaReferer(mediaUrl, fallback))
            "${uri.scheme}://${uri.host}"
        }.getOrDefault(fallback.trimEnd('/'))
    }

    private fun resolvePlayerUrl(rawUrl: String, pageUrl: String): String? {
        if (rawUrl.isBlank() || rawUrl.startsWith("javascript:", ignoreCase = true)) return null
        val cleanedUrl = rawUrl.trim('"', '\'', '`')
        return runCatching { URI(pageUrl).resolve(cleanedUrl).toString() }.getOrNull()
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: fixUrlNull(cleanedUrl)
    }

}