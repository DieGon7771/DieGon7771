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
        
        // SELETTORE PRECISO: Prendi SOLO i container dei film
        val items = doc.select("article.movie-item, article.post, div.movie-item, div[class*='movie']").distinctBy { element ->
            // Evita doppioni: usa l'URL come identificatore unico
            element.selectFirst("a")?.attr("href") ?: element.toString()
        }.mapNotNull {
            it.toSearchResponse()
        }
        
        // Paginazione: controlla se esiste un link "next"
        val hasNext = doc.select("a.next.page-numbers, a[rel='next'], .pagination a:contains(»), a:contains(Successivo)").isNotEmpty()

        return newHomePageResponse(HomePageList(request.name, items), hasNext = hasNext)
    }

    private fun Element.toSearchResponse(): MovieSearchResponse? {
        // 1. TROVA LINK PRINCIPALE in modo affidabile
        val linkElement = this.selectFirst("a[href*='/film/'], a[href*='/serie-tv/']") ?: return null
        val href = linkElement.attr("href").takeIf { it.isNotBlank() } ?: return null
        
        // 2. TROVA TITOLO PRECISO
        val titleElement = this.selectFirst("h2, h3, .entry-title, .title, .movie-title")
        val title = titleElement?.text()?.trim() ?: "Sconosciuto"
        
        // 3. TROVA IMMAGINE CORRETTA (evita placeholder)
        val imgElement = this.selectFirst("img[src*='.jpg'], img[src*='.png'], img[src*='.webp']")
        val poster = when {
            imgElement?.attr("data-src")?.isNotBlank() == true -> imgElement.attr("data-src")
            imgElement?.attr("src")?.isNotBlank() == true -> imgElement.attr("src")
            else -> null
        }
        
        // 4. Pulisci URL immagine (rimuovi parametri di query)
        val cleanPoster = poster?.substringBefore("?")?.substringBefore("&")
        
        // 5. TROVA RATING (se esiste)
        val ratingElement = this.selectFirst(".rating, .imdb-rate, [class*='rate'], .score")
        val ratingText = ratingElement?.text()?.replace("IMDb", "")?.replace("/10", "")?.trim()
        
        return newMovieSearchResponse(title, fixUrl(href)) {
            this.posterUrl = fixUrlNull(cleanPoster)
            ratingText?.let {
                this.score = Score.from(it, 10)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?story=$query&do=search&subaction=search").document
        
        // Usa lo stesso selettore della main page
        return doc.select("article.movie-item, article.post, div.movie-item").mapNotNull {
            it.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        
        // CONTENUTO PRINCIPALE - cerca in più punti
        val content = doc.selectFirst("#dle-content, .single-content, article, main, .content") ?: return null
        val title = content.select("h1, h2").firstOrNull()?.text()?.trim() ?: "Sconosciuto"
        
        // POSTER - cerca immagini di qualità
        val posterElement = content.selectFirst("img.wp-post-image, img.poster, img[src*='poster'], .post-thumb img")
        val poster = fixUrlNull(posterElement?.attr("src") ?: posterElement?.attr("data-src"))
        
        // TRAMA
        val plotElement = content.selectFirst("#sfull, .plot, .description, .entry-content, .trama")
        val plot = plotElement?.ownText()?.substringAfter("Trama:")?.trim() 
                  ?: plotElement?.text()?.substringAfter("Trama:")?.trim()
        
        // RATING
        val ratingElement = content.selectFirst("span.rateIMDB, .imdb-rating, .rating, .score")
        val rating = ratingElement?.text()?.substringAfter("IMDb:")?.trim()
                    ?: ratingElement?.text()?.trim()

        // GENERI e ANNO
        val details = content.select("#details > li, .details li, .metadata li, .info li")
        val genres = details.firstOrNull { it.text().contains("Genere:") }
            ?.select("a")?.map { it.text().trim() } ?: emptyList()
        val year = details.firstOrNull { it.text().contains("Anno:") }
            ?.text()?.substringAfter("Anno:")?.trim()?.toIntOrNull()

        // DETERMINA SE FILM O SERIE
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
            // ESTRAZIONE LINK STREAMING MIGLIORATA
            val streamingLinks = extractStreamingLinks(doc)
            
            newMovieLoadResponse(title, url, TvType.Movie, streamingLinks) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = genres
                this.year = year
                addScore(rating)
            }
        }
    }

    private fun extractStreamingLinks(doc: Document): List<String> {
        val links = mutableListOf<String>()
        
        // 1. Cerca iframe principale
        val mainIframe = doc.select("#player1 > iframe, iframe[src*='mostraguarda']").attr("src")
        if (mainIframe.isNotBlank()) {
            if (mainIframe.contains("mostraguarda")) {
                // Estrai dai mirror di mostraguarda
                try {
                    val mostraDoc = app.get(mainIframe).document
                    val mirrors = mostraDoc.select("ul._player-mirrors > li, .player-mirrors li, .mirror-item")
                    mirrors.forEach { mirror ->
                        val link = mirror.attr("data-link").takeIf { it.isNotBlank() }
                        if (link != null && !link.contains("mostraguarda")) {
                            links.add(fixUrl(link))
                        }
                    }
                } catch (e: Exception) {
                    Log.d("AltaDefinizione", "Error extracting from mostraguarda: ${e.message}")
                }
            } else {
                // Iframe diretto
                links.add(fixUrl(mainIframe))
            }
        }
        
        // 2. Cerca player alternativi
        val altPlayers = doc.select(".player-option, .player-server, [class*='player']")
        altPlayers.forEach { player ->
            val link = player.attr("data-link")?.takeIf { it.isNotBlank() }
                ?: player.selectFirst("iframe")?.attr("src")?.takeIf { it.isNotBlank() }
            
            if (link != null && !link.contains("mostraguarda")) {
                links.add(fixUrl(link))
            }
        }
        
        // 3. Fallback: tutti gli iframe
        if (links.isEmpty()) {
            doc.select("iframe").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank() && !src.contains("mostraguarda")) {
                    links.add(fixUrl(src))
                }
            }
        }
        
        Log.d("AltaDefinizione", "Found ${links.size} streaming links: $links")
        return links.distinct() // Rimuovi duplicati
    }

    private fun getEpisodes(doc: Document, poster: String?): List<Episode> {
        val seasons = doc.selectFirst("div.tab-content, .seasons-container, .episodes-list")
            ?.select("div.tab-pane, .season, .season-content") ?: return emptyList()

        return seasons.mapNotNull { season ->
            val seasonNumber = season.attr("id").substringAfter("season-").toIntOrNull()
                ?: season.selectFirst("[data-season]")?.attr("data-season")?.toIntOrNull()
                ?: 1
            
            val episodes = season.select("li, .episode-item, .episode").mapNotNull { ep ->
                // Estrai link streaming per episodio
                val mirrors = ep.select("div.mirrors > a.mr, .mirrors a, .player-options a, [data-link]")
                    .mapNotNull { it.attr("data-link").takeIf { link -> link.isNotBlank() } }
                    .map { fixUrl(it) }
                
                if (mirrors.isEmpty()) return@mapNotNull null
                
                val epData = ep.select("a").firstOrNull()
                val epNumber = epData?.attr("data-num")?.substringAfter("x")?.toIntOrNull()
                    ?: ep.selectFirst("[data-episode]")?.attr("data-episode")?.toIntOrNull()
                    ?: 1
                val epTitle = epData?.attr("data-title")?.substringBefore(":")?.trim()
                    ?: ep.selectFirst(".episode-title, .title")?.text()?.trim()
                    ?: "Episodio $epNumber"
                val epPlot = epData?.attr("data-title")?.substringAfter(": ")?.trim()
                    ?: ep.selectFirst(".description, .plot")?.text()?.trim()
                    ?: ""
                
                newEpisode(mirrors) {
                    this.season = seasonNumber
                    this.episode = epNumber
                    this.name = epTitle
                    this.description = epPlot
                    this.posterUrl = poster
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
        Log.d("Altadefinizione", "Links to extract: $data")
        
        if (data.isBlank()) return false
        
        val links = parseJson<List<String>>(data)
        var success = false
        
        links.forEach { link ->
            when {
                link.contains("dropload.tv") -> {
                    DroploadExtractor().getUrl(link, mainUrl, subtitleCallback, callback)
                    success = true
                }
                link.contains("supervideo.tv") || link.contains("myvi.ru") -> {
                    MySupervideoExtractor().getUrl(link, mainUrl, subtitleCallback, callback)
                    success = true
                }
                link.contains("vix") || link.contains("stream") -> {
                    // Aggiungi altri extractor se necessario
                    Log.d("Altadefinizione", "Unhandled link type: $link")
                }
                else -> {
                    Log.d("Altadefinizione", "Unknown link type: $link")
                }
            }
        }
        
        return success
    }
    
    // Helper function per fix URL
    private fun fixUrl(url: String): String {
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/$url"
        }
    }
}
