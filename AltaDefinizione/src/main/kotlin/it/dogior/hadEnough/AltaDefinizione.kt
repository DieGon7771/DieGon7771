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
    override var mainUrl = "https://altadefinizionez.click"
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
        
        // Selettore più semplice e affidabile
        val items = doc.select("article, div[class*='movie'], div[class*='film']").mapNotNull {
            it.toSearchResponse()
        }.distinctBy { it.url } // Rimuove duplicati per URL
        
        // Paginazione semplice
        val hasNext = doc.select("a.next, a[rel='next']").isNotEmpty()

        return newHomePageResponse(HomePageList(request.name, items), hasNext = hasNext)
    }

    private fun Element.toSearchResponse(): MovieSearchResponse? {
        // Trova link
        val linkElement = this.selectFirst("a") ?: return null
        val href = linkElement.attr("href").takeIf { it.isNotBlank() } ?: return null
        
        // Trova titolo
        val title = this.selectFirst("h2, h3, h4")?.text()?.trim() ?: "Sconosciuto"
        
        // Trova immagine
        val imgElement = this.selectFirst("img")
        val poster = imgElement?.attr("data-src") ?: imgElement?.attr("src")
        
        return newMovieSearchResponse(title, fixUrlNull(href)) {
            this.posterUrl = fixUrlNull(poster)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?story=$query&do=search&subaction=search").document
        
        return doc.select("article, div[class*='movie']").mapNotNull {
            it.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        
        // Titolo
        val title = doc.selectFirst("h1, h2")?.text()?.trim() ?: "Sconosciuto"
        
        // Poster
        val posterElement = doc.selectFirst("img.wp-post-image, .poster img")
        val poster = fixUrlNull(posterElement?.attr("src"))
        
        // Trama
        val plot = doc.selectFirst("#sfull, .plot")?.text()?.substringAfter("Trama:")?.trim()
        
        // Generi
        val genres = doc.select("#details li:contains(Genere:) a").map { it.text() }
        
        // Anno
        val yearText = doc.selectFirst("#details li:contains(Anno:)")?.text()
        val year = yearText?.substringAfter("Anno:")?.trim()?.toIntOrNull()
        
        // Rating
        val rating = doc.selectFirst(".rateIMDB, .rating")?.text()?.substringAfter("IMDb:")?.trim()

        // Determina se film o serie
        return if (url.contains("/serie-tv/")) {
            val episodes = getEpisodes(doc, poster)
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = genres
                addScore(rating)
                this.year = year
            }
        } else {
            // Estrai link streaming SEMPLIFICATO
            val streamingLinks = mutableListOf<String>()
            
            // 1. Iframe principale
            val mainIframe = doc.select("#player1 iframe").attr("src")
            if (mainIframe.isNotBlank()) {
                streamingLinks.add(mainIframe)
            }
            
            // 2. Altri iframe
            doc.select("iframe").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank() && src != mainIframe) {
                    streamingLinks.add(src)
                }
            }
            
            newMovieLoadResponse(title, url, TvType.Movie, streamingLinks) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = genres
                this.year = year
                addScore(rating)
            }
        }
    }

    private fun getEpisodes(doc: Document, poster: String?): List<Episode> {
        val seasons = doc.select("div.tab-content .tab-pane, .season")
        if (seasons.isEmpty()) return emptyList()

        return seasons.mapIndexed { index, season ->
            val seasonNumber = index + 1
            
            season.select("li, .episode").mapNotNull { ep ->
                val mirrors = ep.select("[data-link]").mapNotNull { it.attr("data-link") }
                if (mirrors.isEmpty()) return@mapNotNull null
                
                val epNumber = ep.selectFirst("[data-num]")?.attr("data-num")?.substringAfter("x")?.toIntOrNull() ?: 1
                val epTitle = ep.selectFirst("a")?.attr("data-title")?.substringBefore(":")?.trim() ?: "Episodio $epNumber"
                
                newEpisode(mirrors) {
                    this.season = seasonNumber
                    this.episode = epNumber
                    this.name = epTitle
                    this.posterUrl = poster
                }
            }
        }.flatten()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        Log.d("Altadefinizione", "Processing links: $data")
        
        if (data.isBlank()) return false
        
        try {
            val links = parseJson<List<String>>(data)
            var success = false
            
            links.forEach { link ->
                when {
                    link.contains("dropload.tv") -> {
                        DroploadExtractor().getUrl(link, mainUrl, subtitleCallback, callback)
                        success = true
                    }
                    link.contains("supervideo.tv") -> {
                        MySupervideoExtractor().getUrl(link, mainUrl, subtitleCallback, callback)
                        success = true
                    }
                    else -> {
                        Log.d("Altadefinizione", "Unsupported link: $link")
                    }
                }
            }
            
            return success
        } catch (e: Exception) {
            Log.d("Altadefinizione", "Error parsing links: ${e.message}")
            return false
        }
    }
}
