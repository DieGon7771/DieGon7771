package it.dogior.hadEnough

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.runAllAsync
import com.lagradost.cloudstream3.toNewSearchResponseList
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.SubtitleHelper
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import it.dogior.hadEnough.SubsExtractors.invokeOpenSubs
import it.dogior.hadEnough.SubsExtractors.invokeWatchsomuch
import java.util.concurrent.TimeUnit

class StremioX(override var mainUrl: String, override var name: String) : TmdbProvider() {
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Others)

    companion object {
        const val TRACKER_LIST_URL = "https://raw.githubusercontent.com/ngosang/trackerslist/master/trackers_best.txt"
        private const val tmdbAPI = "https://api.themoviedb.org/3"
        private const val apiKey = BuildConfig.TMDB_API
        
        // 🔧 AUTO-DETECT FORMATO CHIAVE
        private val isV4Key: Boolean by lazy {
            apiKey.length > 50 && apiKey.startsWith("eyJ")
        }
        
        // 🛡️ HEADERS SOLO PER CHIAVE V4
        private val authHeaders: Map<String, String> by lazy {
            if (isV4Key) {
                mapOf("Authorization" to "Bearer $apiKey", "accept" to "application/json")
            } else {
                emptyMap()
            }
        }
        
        // 🔗 COSTRUISCI URL CON/SENZA api_key
        private fun buildUrl(baseUrl: String): String {
            return if (isV4Key) {
                // Chiave nuova: NO api_key in URL
                baseUrl
            } else {
                // Chiave vecchia: aggiungi api_key
                if (baseUrl.contains("?")) {
                    "$baseUrl&api_key=$apiKey"
                } else {
                    "$baseUrl?api_key=$apiKey"
                }
            }
        }
        
        // 💾 CACHE 12 ORE (mantenuta)
        private class TMDBRequestCache {
            private val cache = mutableMapOf<String, Pair<Long, String>>()
            private val cacheDuration = TimeUnit.HOURS.toMillis(12)  // 12 ORE!
            
            fun getCached(url: String): String? {
                val cached = cache[url]
                return if (cached != null && System.currentTimeMillis() - cached.first < cacheDuration) {
                    cached.second
                } else {
                    null
                }
            }
            
            fun setCached(url: String, data: String) {
                cache[url] = Pair(System.currentTimeMillis(), data)
            }
        }
        
        private val cache = TMDBRequestCache()
        
        // 🌐 MASTER REQUEST FUNCTION CON CACHE (SENZA LIMITI)
        private suspend fun makeTMDBRequest(url: String): String {
            // 1. Controlla cache (12 ore)
            val cached = cache.getCached(url)
            if (cached != null) {
                return cached
            }
            
            // 2. Fai richiesta (NESSUN LIMITE)
            val response = if (isV4Key) {
                app.get(url, headers = authHeaders)
            } else {
                app.get(url)
            }
            
            if (!response.isSuccessful) {
                throw ErrorLoadingException("Errore TMDB API: ${response.code}")
            }
            
            val text = response.text
            cache.setCached(url, text)
            return text
        }

        fun getType(t: String?): TvType {
            return when (t) {
                "movie" -> TvType.Movie
                else -> TvType.TvSeries
            }
        }

        fun getStatus(t: String?): ShowStatus {
            return when (t) {
                "Returning Series" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
        
        // 📊 LOG INIT (solo per debug)
        init {
            println("🔑 StremioX - TMDB Key length: ${apiKey.length}")
            println("🔑 StremioX - Using V4 format: $isV4Key")
            println("💾 StremioX - Cache duration: 12 ore")
            println("🚀 StremioX - NO daily request limits!")
        }
    }

    // 🎯 SEZIONI HOME
    override val mainPage = mainPageOf(
        buildUrl("$tmdbAPI/trending/all/day?region=US") to "Trending",
        buildUrl("$tmdbAPI/movie/popular?region=US") to "Film Popolari",
        buildUrl("$tmdbAPI/tv/popular?region=US") to "Serie TV Popolari"
    )

    private fun getImageUrl(link: String?): String? {
        if (link == null) return null
        return if (link.startsWith("/")) "https://image.tmdb.org/t/p/original/$link" else link
    }

    private fun getOriImageUrl(link: String?): String? {
        if (link == null) return null
        return if (link.startsWith("/")) "https://image.tmdb.org/t/p/original/$link" else link
    }

    override suspend fun getMainPage(
        page: Int, request: MainPageRequest
    ): HomePageResponse {
        val adultQuery =
            if (settingsForProvider.enableAdult) "" else "&without_keywords=190370|13059|226161|195669|190370"
        val type = if (request.data.contains("/movie")) "movie" else "tv"
        
        val responseText = makeTMDBRequest("${request.data}$adultQuery&page=$page")
        val home = parseJson<Results>(responseText)?.results?.mapNotNull { media ->
            media.toSearchResponse(type)
        } ?: throw ErrorLoadingException("Invalid Json response")
        
        return newHomePageResponse(request.name, home)
    }

    private fun Media.toSearchResponse(type: String? = null): SearchResponse? {
        return newMovieSearchResponse(
            title ?: name ?: originalTitle ?: return null,
            Data(id = id, type = mediaType ?: type).toJson(),
            TvType.Movie,
        ) {
            this.posterUrl = getImageUrl(posterPath)
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query,1)?.items

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val url = buildUrl(
            "$tmdbAPI/search/multi?language=en-US&query=$query&page=$page&include_adult=${settingsForProvider.enableAdult}"
        )
        
        val responseText = makeTMDBRequest(url)
        return parseJson<Results>(responseText)?.results?.mapNotNull { media ->
            media.toSearchResponse()
        }?.toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val data = parseJson<Data>(url)
        val type = getType(data.type)
        
        val resUrl = buildUrl(
            if (type == TvType.Movie) {
                "$tmdbAPI/movie/${data.id}?append_to_response=keywords,credits,external_ids,videos,recommendations"
            } else {
                "$tmdbAPI/tv/${data.id}?append_to_response=keywords,credits,external_ids,videos,recommendations"
            }
        )
        
        val responseText = makeTMDBRequest(resUrl)
        val res = parseJson<MediaDetail>(responseText)
            ?: throw ErrorLoadingException("Invalid Json Response")

        val title = res.title ?: res.name ?: return null
        val poster = getOriImageUrl(res.posterPath)
        val bgPoster = getOriImageUrl(res.backdropPath)
        val releaseDate = res.releaseDate ?: res.firstAirDate
        val year = releaseDate?.split("-")?.first()?.toIntOrNull()
        val genres = res.genres?.mapNotNull { it.name }
        val isAnime =
            genres?.contains("Animation") == true && (res.original_language == "zh" || res.original_language == "ja")
        val keywords = res.keywords?.results?.mapNotNull { it.name }.orEmpty()
            .ifEmpty { res.keywords?.keywords?.mapNotNull { it.name } }

        val actors = res.credits?.cast?.mapNotNull { cast ->
            ActorData(
                Actor(
                    cast.name ?: cast.originalName ?: return@mapNotNull null,
                    getImageUrl(cast.profilePath)
                ), roleString = cast.character
            )
        } ?: return null
        val recommendations =
            res.recommendations?.results?.mapNotNull { media -> media.toSearchResponse() }

        val trailer =
            res.videos?.results?.map { "https://www.youtube.com/watch?v=${it.key}" }?.randomOrNull()

        return if (type == TvType.TvSeries) {
            val episodes = res.seasons?.mapNotNull { season ->
                val seasonUrl = buildUrl("$tmdbAPI/tv/${data.id}/season/${season.seasonNumber}")
                val seasonText = makeTMDBRequest(seasonUrl)
                parseJson<MediaDetailEpisodes>(seasonText)?.episodes?.map { eps ->
                        newEpisode(LoadData(
                            res.external_ids?.imdb_id,
                            eps.seasonNumber,
                            eps.episodeNumber
                        ).toJson())
                        {
                            this.name = eps.name + if (isUpcoming(eps.airDate)) " • [UPCOMING]" else ""
                            this.season = eps.seasonNumber
                            this.episode = eps.episodeNumber
                            this.posterUrl = getImageUrl(eps.stillPath)
                            this.score = Score.from10(eps.voteAverage)
                            this.description = eps.overview
                            this.addDate(eps.airDate)
                        }
                    }
            }?.flatten() ?: listOf()
            newTvSeriesLoadResponse(
                title, url, if (isAnime) TvType.Anime else TvType.TvSeries, episodes
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = bgPoster
                this.year = year
                this.plot = res.overview
                this.tags =  keywords.takeIf { !it.isNullOrEmpty() } ?: genres
                this.score = Score.from10(res.vote_average.toString())
                this.showStatus = getStatus(res.status)
                this.recommendations = recommendations
                this.actors = actors
                addTrailer(trailer)
                addTMDbId(data.id.toString())
                addImdbId(res.external_ids?.imdb_id)
            }
        } else {
            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                LoadData(res.external_ids?.imdb_id).toJson()
            ) {
                this.posterUrl = poster
                this.comingSoon = isUpcoming(releaseDate)
                this.backgroundPosterUrl = bgPoster
                this.year = year
                this.plot = res.overview
                this.duration = res.runtime
                this.tags = keywords.takeIf { !it.isNullOrEmpty() } ?: genres
                this.score = Score.from10(res.vote_average.toString())
                this.recommendations = recommendations
                this.actors = actors
                addTrailer(trailer)
                addTMDbId(data.id.toString())
                addImdbId(res.external_ids?.imdb_id)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = parseJson<LoadData>(data)

        runAllAsync(
            {
                invokeMainSource(res.imdbId, res.season, res.episode, subtitleCallback, callback)
            },
            {
                invokeWatchsomuch(res.imdbId, res.season, res.episode, subtitleCallback)
            },
            {
                invokeOpenSubs(res.imdbId, res.season, res.episode, subtitleCallback)
            },
        )

        return true
    }

    private suspend fun invokeMainSource(
        imdbId: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixMainUrl = mainUrl.fixSourceUrl()
        val url = if (season == null) {
            "$fixMainUrl/stream/movie/$imdbId.json"
        } else {
            "$fixMainUrl/stream/series/$imdbId:$season:$episode.json"
        }
        val res = app.get(url, timeout = 120L).parsedSafe<StreamsResponse>()
        res?.streams?.forEach { stream ->
            stream.runCallback(subtitleCallback, callback)
        }
    }

    private data class StreamsResponse(val streams: List<Stream>)
    private data class Subtitle(
        val url: String?,
        val lang: String?,
        val id: String?,
    )

    private data class ProxyHeaders(
        val request: Map<String, String>?,
    )

    private data class BehaviorHints(
        val proxyHeaders: ProxyHeaders?,
        val headers: Map<String, String>?,
    )

    private data class Stream(
        val name: String?,
        val title: String?,
        val url: String?,
        val description: String?,
        val ytId: String?,
        val externalUrl: String?,
        val behaviorHints: BehaviorHints?,
        val infoHash: String?,
        val sources: List<String> = emptyList(),
        val subtitles: List<Subtitle> = emptyList()
    ) {
        suspend fun runCallback(
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
        ) {
            if (url != null) {
                callback.invoke(
                    newExtractorLink(
                        name ?: "",
                        fixSourceName(name, title),
                        url,
                        INFER_TYPE,
                    )
                    {
                        this.quality=getQuality(listOf(description,title,name))
                        this.headers=behaviorHints?.proxyHeaders?.request ?: behaviorHints?.headers ?: mapOf()
                    }
                )
                subtitles.map { sub ->
                    subtitleCallback.invoke(
                        newSubtitleFile(
                            SubtitleHelper.fromTagToEnglishLanguageName(sub.lang ?: "") ?: sub.lang
                            ?: "",
                            sub.url ?: return@map
                        )
                    )
                }
            }
            if (ytId != null) {
                loadExtractor("https://www.youtube.com/watch?v=$ytId", subtitleCallback, callback)
            }
            if (externalUrl != null) {
                loadExtractor(externalUrl, subtitleCallback, callback)
            }
            if (infoHash != null) {
                val resp = app.get(TRACKER_LIST_URL).text
                val otherTrackers = resp
                    .split("\n")
                    .filterIndexed { i, _ -> i % 2 == 0 }
                    .filter { s -> s.isNotEmpty() }.joinToString("") { "&tr=$it" }

                val sourceTrackers = sources
                    .filter { it.startsWith("tracker:") }
                    .map { it.removePrefix("tracker:") }
                    .filter { s -> s.isNotEmpty() }.joinToString("") { "&tr=$it" }

                val magnet = "magnet:?xt=urn:btih:${infoHash}${sourceTrackers}${otherTrackers}"
                callback.invoke(
                    newExtractorLink(
                        name ?: "",
                        title ?: name ?: "",
                        magnet,
                    )
                    {
                        this.quality=Qualities.Unknown.value
                    }
                )
            }
        }
    }

    data class LoadData(
        val imdbId: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
    )

    data class Data(
        val id: Int? = null,
        val type: String? = null,
        val aniId: String? = null,
        val malId: Int? = null,
    )

    data class Results(
        @JsonProperty("results") val results: ArrayList<Media>? = arrayListOf(),
    )

    data class Media(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("original_title") val originalTitle: String? = null,
        @JsonProperty("media_type") val mediaType: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
    )

    data class Genres(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
    )

    data class Keywords(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
    )

    data class KeywordResults(
        @JsonProperty("results") val results: ArrayList<Keywords>? = arrayListOf(),
        @JsonProperty("keywords") val keywords: ArrayList<Keywords>? = arrayListOf(),
    )

    data class Seasons(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("season_number") val seasonNumber: Int? = null,
        @JsonProperty("air_date") val airDate: String? = null,
    )

    data class Cast(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("original_name") val originalName: String? = null,
        @JsonProperty("character") val character: String? = null,
        @JsonProperty("known_for_department") val knownForDepartment: String? = null,
        @JsonProperty("profile_path") val profilePath: String? = null,
    )

    data class Episodes(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("air_date") val airDate: String? = null,
        @JsonProperty("still_path") val stillPath: String? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null,
        @JsonProperty("episode_number") val episodeNumber: Int? = null,
        @JsonProperty("season_number") val seasonNumber: Int? = null,
    )

    data class MediaDetailEpisodes(
        @JsonProperty("episodes") val episodes: ArrayList<Episodes>? = arrayListOf(),
    )

    data class Trailers(
        @JsonProperty("key") val key: String? = null,
    )

    data class ResultsTrailer(
        @JsonProperty("results") val results: ArrayList<Trailers>? = arrayListOf(),
    )

    data class ExternalIds(
        @JsonProperty("imdb_id") val imdb_id: String? = null,
        @JsonProperty("tvdb_id") val tvdb_id: String? = null,
    )

    data class Credits(
        @JsonProperty("cast") val cast: ArrayList<Cast>? = arrayListOf(),
    )

    data class ResultsRecommendations(
        @JsonProperty("results") val results: ArrayList<Media>? = arrayListOf(),
    )

    data class LastEpisodeToAir(
        @JsonProperty("episode_number") val episode_number: Int? = null,
        @JsonProperty("season_number") val season_number: Int? = null,
    )

    data class MediaDetail(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("imdb_id") val imdbId: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("original_title") val originalTitle: String? = null,
        @JsonProperty("original_name") val originalName: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("backdrop_path") val backdropPath: String? = null,
        @JsonProperty("release_date") val releaseDate: String? = null,
        @JsonProperty("first_air_date") val firstAirDate: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("runtime") val runtime: Int? = null,
        @JsonProperty("vote_average") val vote_average: Any? = null,
        @JsonProperty("original_language") val original_language: String? = null,
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("genres") val genres: ArrayList<Genres>? = arrayListOf(),
        @JsonProperty("keywords") val keywords: KeywordResults? = null,
        @JsonProperty("last_episode_to_air") val last_episode_to_air: LastEpisodeToAir? = null,
        @JsonProperty("seasons") val seasons: ArrayList<Seasons>? = arrayListOf(),
        @JsonProperty("videos") val videos: ResultsTrailer? = null,
        @JsonProperty("external_ids") val external_ids: ExternalIds? = null,
        @JsonProperty("credits") val credits: Credits? = null,
        @JsonProperty("recommendations") val recommendations: ResultsRecommendations? = null,
    )
}
