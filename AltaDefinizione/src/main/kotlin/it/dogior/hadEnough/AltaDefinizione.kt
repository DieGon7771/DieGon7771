package it.dogior.hadEnough

import com.lagradost.api.Log
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import it.dogior.hadEnough.extractors.DroploadExtractor
import it.dogior.hadEnough.extractors.MySupervideoExtractor
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class AltaDefinizione : MainAPI() {
    override var mainUrl = "https://altadefinizionegratis.skin"
    override var name = "AltaDefinizione"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Documentary)
    override var lang = "it"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "$mainUrl/film/" to "Ultimi Aggiunti",
        "$mainUrl/cinema/" to "Ora al Cinema",
        "$mainUrl/netflix-streaming/" to "Netflix",
        "$mainUrl/animazione/" to "Animazione",
        "$mainUrl/avventura/" to "Avventura",
        "$mainUrl/azione/" to "Azione",
        "$mainUrl/biografico/" to "Biografico",
        "$mainUrl/commedia/" to "Commedia",
        "$mainUrl/crime/" to "Crimine",
        "$mainUrl/documentario/" to "Documentario",
        "$mainUrl/drammatico/" to "Drammatico",
        "$mainUrl/erotico/" to "Erotico",
        "$mainUrl/famiglia/" to "Famiglia",
        "$mainUrl/fantascienza/" to "Fantascienza",
        "$mainUrl/fantasy/" to "Fantasy",
        "$mainUrl/giallo/" to "Giallo",
        "$mainUrl/guerra/" to "Guerra",
        "$mainUrl/horror/" to "Horror",
        "$mainUrl/musical/" to "Musical",
        "$mainUrl/poliziesco/" to "Poliziesco",
        "$mainUrl/romantico/" to "Romantico",
        "$mainUrl/sportivo/" to "Sportivo",
        "$mainUrl/storico-streaming/" to "Storico",
        "$mainUrl/thriller/" to "Thriller",
        "$mainUrl/western/" to "Western"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "${request.data}page/$page/"
        val doc = app.get(url).document
        
        // 🔥 SELEZTORI CORRETTI - basati sull'HTML reale
        val items = doc.select("#dle-content > .col-lg-3").mapNotNull {
            it.toSearchResponse()
        }
        
        // Paginazione - cercata nell'HTML reale
        val pagination = doc.select("div.pagination > a").last()?.text()?.toIntOrNull()
        val hasNext = page < (pagination ?: 1) || doc.select("a.next").isNotEmpty()
        
        Log.d("AltaDefinizione", "Page $page: Found ${items.size} items, hasNext: $hasNext")

        return newHomePageResponse(HomePageList(request.name, items), hasNext = hasNext)
    }

    private fun Element.toSearchResponse(): MovieSearchResponse? {
        try {
            // 🔥 SELEZTORI CORRETTI - basati sull'HTML reale
            val wrapper = this.selectFirst(".wrapperImage") ?: return null
            val titleElement = this.selectFirst("h2.titleFilm > a") ?: return null
            
            val href = titleElement.attr("href")
            val title = titleElement.text().trim()
            
            // Immagine dal wrapper
            val img = wrapper.selectFirst("img")
            val poster = img?.attr("src") ?: img?.attr("data-src")
            
            // Rating - potrebbe essere in un elemento vicino
            val ratingElement = this.selectFirst("div.imdb-rate, span.rating")
            val ratingText = ratingElement?.ownText() ?: ratingElement?.text()
            val rating = ratingText?.filter { it.isDigit() || it == '.' || it == ',' }
                ?.replace(",", ".")?.toFloatOrNull()
            
            Log.d("AltaDefinizione", "Parsed: $title -> $href, rating: $rating")
            
            return newMovieSearchResponse(title, fixUrl(href)) {
                this.posterUrl = fixUrlNull(poster)
                if (rating != null) {
                    this.score = Score(rating)
                }
            }
        } catch (e: Exception) {
            Log.d("AltaDefinizione", "Error in toSearchResponse: ${e.message}")
            return null
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/index.php?do=search"
        val doc = app.post(url, data = mapOf(
            "do" to "search",
            "subaction" to "search",
            "story" to query
        )).document
        
        // Usa gli stessi selettori della homepage per i risultati
        val items = doc.select("#dle-content > .col-lg-3").mapNotNull {
            it.toSearchResponse()
        }
        
        Log.d("AltaDefinizione", "Search '$query': Found ${items.size} items")
        return items
    }

    override suspend fun load(url: String): LoadResponse? {
        try {
            val doc = app.get(url).document
            val content = doc.selectFirst("#dle-content") ?: return null
            
            // Titolo - cerca in vari punti
            val title = content.select("h1, h2").firstOrNull { 
                !it.text().isNullOrEmpty() && it.text() != "Sconosciuto" 
            }?.text()?.trim() ?: "Sconosciuto"
            
            // Poster
            val poster = fixUrlNull(
                content.select("img.wp-post-image").attr("src")
            )
            
            // Trama
            val plotElement = content.selectFirst("#sfull")
            val plot = plotElement?.ownText()?.substringAfter("Trama: ")
                ?: plotElement?.text()?.substringAfter("Trama: ")
            
            // Rating
            val rating = content.selectFirst("span.rateIMDB")?.text()
                ?.substringAfter("IMDb:")
                ?.trim()
                ?.replace(",", ".")
            
            // Dettagli
            val details = content.select("#details > li")
            val genres = details.firstOrNull { it.text().contains("Genere:", ignoreCase = true) }
                ?.select("a")?.map { it.text() } ?: emptyList()
            
            val yearElement = details.firstOrNull { it.text().contains("Anno:", ignoreCase = true) }
            val year = yearElement?.select("a")?.last()?.text()?.toIntOrNull()
                ?: yearElement?.ownText()?.substringAfter("Anno: ")?.trim()?.toIntOrNull()
            
            Log.d("AltaDefinizione", "Loading: $title, year: $year, genres: $genres")
            
            // Serie TV o Film?
            return if (url.contains("/serie-tv/")) {
                val episodes = getEpisodes(doc, poster)
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.plot = plot
                    this.tags = genres
                    if (rating != null) {
                        addScore(rating)
                    }
                }
            } else {
                val mirrors = getMovieMirrors(doc)
                newMovieLoadResponse(title, url, TvType.Movie, mirrors) {
                    this.posterUrl = poster
                    this.plot = plot
                    this.tags = genres
                    this.year = year
                    if (rating != null) {
                        addScore(rating)
                    }
                }
            }
        } catch (e: Exception) {
            Log.d("AltaDefinizione", "Error in load($url): ${e.message}")
            return null
        }
    }

    private suspend fun getMovieMirrors(doc: Document): List<String> {
        val mirrors = mutableListOf<String>()
        
        // Metodo originale
        val mostraGuardaLink = doc.select("#player1 > iframe").attr("src")
        if (mostraGuardaLink.isNotBlank() && mostraGuardaLink.contains("mostraguarda")) {
            try {
                val mostraGuarda = app.get(mostraGuardaLink).document
                val newMirrors = mostraGuarda.select("ul._player-mirrors > li").mapNotNull {
                    val link = it.attr("data-link")
                    if (link.contains("mostraguarda")) null else fixUrlNull(link)
                }
                mirrors.addAll(newMirrors)
            } catch (e: Exception) {
                Log.d("AltaDefinizione", "Error getting mostraGuarda mirrors: ${e.message}")
            }
        }
        
        // Alternative
        val playerElements = doc.select("iframe[src*='embed'], iframe[src*='player']")
        playerElements.forEach { player ->
            val src = player.attr("src")
            if (src.isNotBlank() && !src.contains("mostraguarda")) {
                mirrors.add(fixUrl(src))
            }
        }
        
        Log.d("AltaDefinizione", "Found ${mirrors.size} mirrors")
        return mirrors.distinct()
    }

    private fun getEpisodes(doc: Document, poster: String?): List<Episode> {
        val seasons = doc.selectFirst("div.tab-content")?.select("div.tab-pane") 
            ?: return emptyList()

        return seasons.mapIndexed { seasonIndex, seasonElement ->
            val seasonNumber = seasonElement.attr("id").substringAfter("season-").toIntOrNull()
                ?: (seasonIndex + 1)
            
            val episodes = seasonElement.select("li").mapNotNull { ep ->
                try {
                    val mirrors = ep.select("div.mirrors > a.mr").map { 
                        it.attr("data-link") 
                    }.filter { it.isNotBlank() }
                    
                    val epData = ep.select("a").firstOrNull()
                    val epNumber = epData?.attr("data-num")?.substringAfter("x")?.toIntOrNull()
                        ?: return@mapNotNull null
                    
                    val epTitle = epData.attr("data-title").substringBefore(":")
                    val epPlot = epData.attr("data-title").substringAfter(": ")
                    
                    newEpisode(mirrors) {
                        this.season = seasonNumber
                        this.episode = epNumber
                        this.name = epTitle
                        this.description = epPlot
                        this.posterUrl = poster
                    }
                } catch (e: Exception) {
                    Log.d("AltaDefinizione", "Error parsing episode: ${e.message}")
                    null
                }
            }
            episodes
        }.flatten()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        Log.d("AltaDefinizione", "Loading links: $data")
        try {
            val links = parseJson<List<String>>(data)
            links.forEach { link ->
                try {
                    when {
                        link.contains("dropload.tv") -> {
                            DroploadExtractor().getUrl(link, null, subtitleCallback, callback)
                        }
                        link.contains("supervideo") -> {
                            MySupervideoExtractor().getUrl(link, null, subtitleCallback, callback)
                        }
                        else -> {
                            loadExtractor(link, null, subtitleCallback, callback)
                        }
                    }
                } catch (e: Exception) {
                    Log.d("AltaDefinizione", "Error extracting $link: ${e.message}")
                }
            }
            return true
        } catch (e: Exception) {
            Log.d("AltaDefinizione", "Error in loadLinks: ${e.message}")
            return false
        }
    }
}
