package it.dogior.hadEnough

import com.lagradost.api.Log
import com.lagradost.cloudstream3.APIHolder.capitalize
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addRating
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import okhttp3.HttpUrl.Companion.toHttpUrl

class StreamingCommunity : MainAPI() {
    override var mainUrl = Companion.mainUrl       // include /it
    override var name = Companion.name
    override var supportedTypes =
        setOf(TvType.Movie, TvType.TvSeries, TvType.Cartoon, TvType.Documentary)
    override var lang = "it"
    override val hasMainPage = true

    companion object {
        private var inertiaVersion = ""
        private val headers = mutableMapOf(
            "Cookie" to "",
            "X-Inertia" to true.toString(),
            "X-Inertia-Version" to inertiaVersion,
            "X-Requested-With" to "XMLHttpRequest",
        )
        val mainUrl = "https://streamingcommunityz.bid/it"   // per browse/iframe
        private val baseUrl = "https://streamingcommunityz.bid" // per API
        var name = "StreamingCommunity"
    }

    private val sectionNamesList = mainPageOf(
        "$mainUrl/browse/top10" to "Top 10 di oggi",
        "$mainUrl/browse/trending" to "I Titoli Del Momento",
        "$mainUrl/browse/latest" to "Aggiunti di Recente",
        "$mainUrl/browse/comingsoon" to "In Arrivo",
        "$mainUrl/browse/genre?g=Animation" to "Animazione",
        "$mainUrl/browse/genre?g=Adventure" to "Avventura",
        "$mainUrl/browse/genre?g=Action" to "Azione",
        "$mainUrl/browse/genre?g=Comedy" to "Commedia",
        "$mainUrl/browse/genre?g=Crime" to "Crime",
        "$mainUrl/browse/genre?g=Documentary" to "Documentario",
        "$mainUrl/browse/genre?g=Drama" to "Dramma",
        "$mainUrl/browse/genre?g=Family" to "Famiglia",
        "$mainUrl/browse/genre?g=Science Fiction" to "Fantascienza",
        "$mainUrl/browse/genre?g=Fantasy" to "Fantasy",
        "$mainUrl/browse/genre?g=Horror" to "Horror",
        "$mainUrl/browse/genre?g=Reality" to "Reality",
        "$mainUrl/browse/genre?g=Romance" to "Romance",
        "$mainUrl/browse/genre?g=Thriller" to "Thriller",
    )
    override val mainPage = sectionNamesList

    private suspend fun setupHeaders() {
        val response = app.get("$mainUrl/archive")
        val cookies = response.cookies
        headers["Cookie"] = cookies.map { it.key + "=" + it.value }.joinToString("; ")
        val page = response.document
        val inertiaPageObject = page.select("#app").attr("data-page")
        inertiaVersion = inertiaPageObject.substringAfter("\"version\":\"").substringBefore("\"")
        headers["X-Inertia-Version"] = inertiaVersion
    }

    private fun searchResponseBuilder(listJson: List<Title>): List<SearchResponse> {
        val domain = mainUrl.substringAfter("://").substringBeforeLast("/")
        return listJson.filter { it.type == "movie" || it.type == "tv" }.map { title ->
            val url = "$mainUrl/titles/${title.id}-${title.slug}"
            if (title.type == "tv") {
                newTvSeriesSearchResponse(title.name, url) { posterUrl = "https://cdn.$domain/images/${title.getPoster()}" }
            } else {
                newMovieSearchResponse(title.name, url) { posterUrl = "https://cdn.$domain/images/${title.getPoster()}" }
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url: String = baseUrl + "/api" + request.data.substringAfter(baseUrl)
        val params = mutableMapOf("lang" to "it")

        val section = request.data.substringAfterLast("/")
        if (section !in listOf("top10", "latest", "trending")) {
            val genere = url.substringAfterLast('=').substringBefore('?')
            url.substringBeforeLast('?').let { params["g"] = genere }
        }

        if (page > 0) params["offset"] = ((page - 1) * 60).toString()

        val response = app.get(url, params = params)
        val responseString = response.body.string()
        val responseJson = parseJson<Section>(responseString)
        val titlesList = searchResponseBuilder(responseJson.titles)

        val hasNextPage = response.okhttpResponse.request.url.queryParameter("offset")?.toIntOrNull()?.let { it < 120 } ?: true && titlesList.size == 60

        return newHomePageResponse(
            HomePageList(name = request.name, list = titlesList, isHorizontalImages = false),
            hasNextPage
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$baseUrl/search"
        val params = mapOf("q" to query)

        if (headers["Cookie"].isNullOrEmpty()) setupHeaders()
        val response = app.get(url, params = params, headers = headers).body.string()
        val result = parseJson<InertiaResponse>(response)
        return searchResponseBuilder(result.props.titles!!)
    }

    override suspend fun load(url: String): LoadResponse {
        val actualUrl = getActualUrl(url)
        if (headers["Cookie"].isNullOrEmpty()) setupHeaders()
        val response = app.get(actualUrl, headers = headers)
        val responseBody = response.body.string()
        val props = parseJson<InertiaResponse>(responseBody).props
        val title = props.title!!
        val genres = title.genres.map { it.name.capitalize() }
        val domain = mainUrl.substringAfter("://").substringBeforeLast("/")
        val year = title.releaseDate?.substringBefore('-')?.toIntOrNull()
        val related = props.sliders?.getOrNull(0)
        val trailers = title.trailers?.mapNotNull { it.getYoutubeUrl() }

        if (title.type == "tv") {
            val episodes: List<Episode> = getEpisodes(props)
            return newTvSeriesLoadResponse(title.name, actualUrl, TvType.TvSeries, episodes) {
                posterUrl = "https://cdn.$domain/images/${title.getBackgroundImageId()}"
                this.tags = genres
                this.episodes = episodes
                this.year = year
                this.plot = title.plot
                title.age?.let { this.contentRating = "$it+" }
                this.recommendations = related?.titles?.let { searchResponseBuilder(it) }
                title.imdbId?.let { addImdbId(it) }
                title.tmdbId?.let { addTMDbId(it.toString()) }
                addActors(title.mainActors?.map { it.name })
                addRating(title.score)
                if (!trailers.isNullOrEmpty()) addTrailer(trailers)
            }
        } else {
            return newMovieLoadResponse(title.name, actualUrl, TvType.Movie, dataUrl = "$mainUrl/iframe/${title.id}&canPlayFHD=1") {
                posterUrl = "https://cdn.$domain/images/${title.getBackgroundImageId()}"
                this.tags = genres
                this.year = year
                this.plot = title.plot
                title.age?.let { this.contentRating = "$it+" }
                this.recommendations = related?.titles?.let { searchResponseBuilder(it) }
                addActors(title.mainActors?.map { it.name })
                addRating(title.score)
                title.imdbId?.let { addImdbId(it) }
                title.tmdbId?.let { addTMDbId(it.toString()) }
                title.runtime?.let { this.duration = it }
                if (!trailers.isNullOrEmpty()) addTrailer(trailers)
            }
        }
    }

    private fun getActualUrl(url: String) =
        if (!url.contains(mainUrl)) {
            val replacingValue = if (url.contains("/it/")) mainUrl.toHttpUrl().host else mainUrl.toHttpUrl().host + "/it"
            url.replace(url.toHttpUrl().host, replacingValue)
        } else url

    private suspend fun getEpisodes(props: Props): List<Episode> {
        val episodeList = mutableListOf<Episode>()
        val title = props.title
        title?.seasons?.forEach { season ->
            val responseEpisodes = mutableListOf<it.dogior.hadEnough.Episode>()
            if (season.id == props.loadedSeason!!.id) {
                responseEpisodes.addAll(props.loadedSeason.episodes!!)
            } else {
                if (inertiaVersion.isEmpty()) setupHeaders()
                val url = "$mainUrl/titles/${title.id}-${title.slug}/season-${season.number}"
                val obj = parseJson<InertiaResponse>(app.get(url, headers = headers).body.string())
                responseEpisodes.addAll(obj.props.loadedSeason?.episodes!!)
            }
            responseEpisodes.forEach { ep ->
                episodeList.add(newEpisode("$mainUrl/iframe/${title.id}?episode_id=${ep.id}&canPlayFHD=1") {
                    name = ep.name
                    posterUrl = props.cdnUrl + "/images/" + ep.getCover()
                    description = ep.plot
                    episode = ep.number
                    season = season.number
                    runTime = ep.duration
                })
            }
        }
        return episodeList
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        StreamingCommunityExtractor().getUrl(
            url = data,
            referer = mainUrl,
            subtitleCallback = subtitleCallback,
            callback = callback
        )
        return false
    }
}
