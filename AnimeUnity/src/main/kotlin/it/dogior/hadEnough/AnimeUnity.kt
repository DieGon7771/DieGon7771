package it.dogior.hadEnough

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addRating
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.Locale

typealias Str = BooleanOrString.AsString

const val TAG = "AnimeUnity"

class AnimeUnity : MainAPI() {
    override var mainUrl = Companion.mainUrl
    override var name = Companion.name
    override var supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)
    override var lang = "it"
    override val hasMainPage = true
    override val hasQuickSearch: Boolean = true

    companion object {
        @Suppress("ConstPropertyName")
        const val mainUrl = "https://www.animeunity.so"
        var name = "AnimeUnity"
        var headers = mapOf(
            "Host" to mainUrl.toHttpUrl().host,
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0",
            "Accept" to "application/json, text/plain, */*",
            "Accept-Language" to "it-IT,it;q=0.8,en-US;q=0.5,en;q=0.3",
            "Accept-Encoding" to "gzip, deflate, br",
            "Referer" to "$mainUrl/archivio",
            "Origin" to mainUrl,
            "Connection" to "keep-alive",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "same-origin",
            "TE" to "trailers"
        ).toMutableMap()
    }

    private val sectionNamesList = mainPageOf(
        "$mainUrl/archivio" to "In Corso",
        "$mainUrl/archivio" to "Popolari",
        "$mainUrl/archivio" to "I migliori",
        "$mainUrl/archivio" to "In Arrivo",
    )

    override val mainPage = sectionNamesList

    private suspend fun setupHeadersAndCookies(): Boolean {
        return try {
            val response = app.get("$mainUrl/archivio", headers = headers)
            
            // Estrai il token CSRF dalla pagina
            val csrfToken = response.document.select("meta[name=csrf-token]").attr("content")
            
            // Aggiorna gli headers con il token CSRF
            headers["X-CSRF-TOKEN"] = csrfToken
            headers["X-Requested-With"] = "XMLHttpRequest"
            
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun resetHeadersAndCookies() {
        headers.clear()
        headers.putAll(
            mapOf(
                "Host" to mainUrl.toHttpUrl().host,
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0",
                "Accept" to "application/json, text/plain, */*",
                "Accept-Language" to "it-IT,it;q=0.8,en-US;q=0.5,en;q=0.3",
                "Referer" to "$mainUrl/archivio"
            )
        )
    }

    private suspend fun searchResponseBuilder(objectList: List<Anime>): List<SearchResponse> {
        return objectList.mapNotNull { anime ->
            try {
                val title = (anime.titleIt ?: anime.titleEng ?: anime.title ?: return@mapNotNull null)

                val poster = getImage(anime.imageUrl, anime.anilistId)

                newAnimeSearchResponse(
                    name = title.replace(" (ITA)", ""),
                    url = "$mainUrl/anime/${anime.id}-${anime.slug}",
                    type = when {
                        anime.type == "TV" -> TvType.Anime
                        anime.type == "Movie" || anime.episodesCount == 1 -> TvType.AnimeMovie
                        else -> TvType.OVA
                    }
                ).apply {
                    addDubStatus(anime.dub == 1 || title.contains("(ITA)"))
                    addPoster(poster)
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    private suspend fun getImage(imageUrl: String?, anilistId: Int?): String? {
        if (!imageUrl.isNullOrEmpty()) {
            try {
                return "https://img.animeunity.so/anime/${imageUrl.substringAfterLast("/")}"
            } catch (_: Exception) {
            }
        }
        return anilistId?.let { getAnilistPoster(it) }
    }

    private suspend fun getAnilistPoster(anilistId: Int): String? {
        return try {
            val query = """
            query (${'$'}id: Int) {
                Media(id: ${'$'}id, type: ANIME) {
                    coverImage {
                        large
                        medium
                    }
                }
            }
        """.trimIndent()

            val body = mapOf(
                "query" to query,
                "variables" to """{"id":$anilistId}"""
            )
            val response = app.post("https://graphql.anilist.co", data = body)
            val anilistObj = parseJson<AnilistResponse>(response.text)
            anilistObj.data.media.coverImage?.large ?: anilistObj.data.media.coverImage?.medium
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/archivio/get-animes"
        
        if (!headers.containsKey("X-CSRF-TOKEN")) {
            resetHeadersAndCookies()
            setupHeadersAndCookies()
        }

        val requestData = getDataPerHomeSection(request.name)
        val offset = (page - 1) * 30
        requestData.offset = offset

        val requestBody = requestData.toRequestBody()

        try {
            val response = app.post(url, headers = headers, requestBody = requestBody)
            
            // Verifica che la risposta sia JSON valido
            if (!response.text.trim().startsWith('{') && !response.text.trim().startsWith('[')) {
                throw Exception("Risposta non JSON: ${response.text.take(100)}")
            }
            
            val responseObject = parseJson<ApiResponse>(response.text)
            val titles = responseObject.titles ?: emptyList()

            val hasNextPage = titles.size == 30
            return newHomePageResponse(
                HomePageList(
                    name = request.name,
                    list = searchResponseBuilder(titles),
                    isHorizontalImages = false
                ), hasNextPage
            )
        } catch (e: Exception) {
            // Se c'è un errore, resetta gli headers e ritorna una pagina vuota
            resetHeadersAndCookies()
            return newHomePageResponse(
                HomePageList(
                    name = request.name,
                    list = emptyList(),
                    isHorizontalImages = false
                ), false
            )
        }
    }

    private fun getDataPerHomeSection(section: String) = when (section) {
        "Popolari" -> RequestData(orderBy = Str("Popolarità"), dubbed = 0)
        "In Arrivo" -> RequestData(status = Str("In Uscita"), dubbed = 0)
        "I migliori" -> RequestData(orderBy = Str("Valutazione"), dubbed = 0)
        "In Corso" -> RequestData(orderBy = Str("Popolarità"), status = Str("In Corso"), dubbed = 0)
        else -> RequestData()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/archivio/get-animes"

        resetHeadersAndCookies()
        if (!setupHeadersAndCookies()) {
            return emptyList()
        }

        try {
            val requestBody = RequestData(title = query, dubbed = 0).toRequestBody()
            val response = app.post(url, headers = headers, requestBody = requestBody)
            
            // Verifica che la risposta sia JSON
            if (!response.text.trim().startsWith('{') && !response.text.trim().startsWith('[')) {
                return emptyList()
            }
            
            val responseObject = parseJson<ApiResponse>(response.text)
            return searchResponseBuilder(responseObject.titles ?: emptyList())
        } catch (e: Exception) {
            return emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        resetHeadersAndCookies()
        setupHeadersAndCookies()
        
        return try {
            val animePage = app.get(url).document

            val relatedAnimeJsonArray = animePage.select("layout-items").attr("items-json")
            val relatedAnime = parseJson<List<Anime>>(relatedAnimeJsonArray)

            val videoPlayer = animePage.select("video-player")
            val anime = parseJson<Anime>(videoPlayer.attr("anime"))

            val eps = parseJson<List<Episode>>(videoPlayer.attr("episodes"))
            val totalEps = videoPlayer.attr("episodes_count").toInt()
            
            val episodes = eps.map {
                newEpisode("$url/${it.id}") {
                    this.episode = it.number.toIntOrNull()
                }
            }.toMutableList()

            // Gestione episodi aggiuntivi se necessario
            if (totalEps > eps.size) {
                for (i in 2..((totalEps / 120) + 1)) {
                    val startRange = 1 + (i - 1) * 120
                    val endRange = minOf(i * 120, totalEps)
                    
                    val infoUrl = "$mainUrl/info_api/${anime.id}/1?start_range=$startRange&end_range=$endRange"
                    val infoResponse = app.get(infoUrl)
                    
                    if (infoResponse.text.trim().startsWith('{')) {
                        val animeInfo = parseJson<AnimeInfo>(infoResponse.text)
                        episodes.addAll(animeInfo.episodes.map {
                            newEpisode("$url/${it.id}") {
                                this.episode = it.number.toIntOrNull()
                            }
                        })
                    }
                }
            }

            val title = anime.titleIt ?: anime.titleEng ?: anime.title!!
            val relatedAnimes = relatedAnime.mapNotNull {
                try {
                    val relatedTitle = (it.titleIt ?: it.titleEng ?: it.title!!)
                    val poster = getImage(it.imageUrl, it.anilistId)
                    newAnimeSearchResponse(
                        name = relatedTitle.replace(" (ITA)", ""),
                        url = "$mainUrl/anime/${it.id}-${it.slug}",
                        type = when {
                            it.type == "TV" -> TvType.Anime
                            it.type == "Movie" || it.episodesCount == 1 -> TvType.AnimeMovie
                            else -> TvType.OVA
                        }
                    ) {
                        addDubStatus(it.dub == 1 || relatedTitle.contains("(ITA)"))
                        addPoster(poster)
                    }
                } catch (e: Exception) {
                    null
                }
            }

            newAnimeLoadResponse(
                name = title.replace(" (ITA)", ""),
                url = url,
                type = when {
                    anime.type == "TV" -> TvType.Anime
                    anime.type == "Movie" || anime.episodesCount == 1 -> TvType.AnimeMovie
                    else -> TvType.OVA
                }
            ) {
                this.posterUrl = getImage(anime.imageUrl, anime.anilistId)
                anime.cover?.let { this.backgroundPosterUrl = getBanner(it) }
                this.year = anime.date.toIntOrNull()
                addRating(anime.score)
                addDuration("${anime.episodesLength} minuti")
                addEpisodes(if (anime.dub == 1) DubStatus.Dubbed else DubStatus.Subbed, episodes)
                addAniListId(anime.anilistId)
                addMalId(anime.malId)
                this.plot = anime.plot
                val doppiato = if (anime.dub == 1 || title.contains("(ITA)")) "\uD83C\uDDEE\uD83C\uDDF9 Italiano" else "\uD83C\uDDEF\uD83C\uDDF5 Giapponese"
                this.tags = listOf(doppiato) + anime.genres.map { genre ->
                    genre.name.replaceFirstChar {
                        if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
                    }
                }
                this.comingSoon = anime.status == "In uscita prossimamente"
                this.recommendations = relatedAnimes
            }
        } catch (e: Exception) {
            throw ErrorLoadingException("Impossibile caricare l'anime: ${e.message}")
        }
    }

    private fun getBanner(imageUrl: String): String {
        return try {
            "https://img.animeunity.so/anime/${imageUrl.substringAfterLast("/")}"
        } catch (_: Exception) {
            imageUrl
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        try {
            val document = app.get(data).document
            val sourceUrl = document.select("video-player").attr("embed_url")
            
            AnimeUnityExtractor().getUrl(
                url = sourceUrl,
                referer = mainUrl,
                subtitleCallback = subtitleCallback,
                callback = callback
            )
            return true
        } catch (e: Exception) {
            return false
        }
    }
}

// Aggiungi queste classi data se non le hai già
data class ApiResponse(
    val titles: List<Anime>? = emptyList(),
    val current_page: Int? = 1,
    val last_page: Int? = 1
)

data class RequestData(
    var title: String? = null,
    var orderBy: Str? = null,
    var status: Str? = null,
    var dubbed: Int? = null,
    var offset: Int? = null
) {
    fun toRequestBody(): String {
        val map = mutableMapOf<String, Any?>()
        title?.let { map["title"] = it }
        orderBy?.let { map["order_by"] = it.value }
        status?.let { map["status"] = it.value }
        dubbed?.let { map["dubbed"] = it }
        offset?.let { map["offset"] = it }
        return map.toJsonString()
    }
    
    private fun Map<String, Any?>.toJsonString(): String {
        return entries.joinToString(",", "{", "}") { (key, value) ->
            "\"$key\":${if (value is String) "\"$value\"" else value}"
        }
    }
}
