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
        
        // NUOVO SELETTORE: gli articoli che contengono i film/serie
        val items = doc.select("article, [class*='movie'], [class*='film']").mapNotNull {
            it.toSearchResponse()
        }
        
        // Paginazione migliorata
        val hasNext = doc.select("a:contains(Successivo), a:contains(»), .next, a.page-numbers:not(.current)").lastOrNull()?.text()?.isNotBlank() == true

        return newHomePageResponse(HomePageList(request.name, items), hasNext = hasNext)
    }

    private fun Element.toSearchResponse(): MovieSearchResponse? {
        // Cerca il titolo e link in vari punti
        val titleElement = this.selectFirst("h2 a, h3 a, h4 a, .entry-title a, .title a") ?: return null
        val title = titleElement.text().trim()
        val href = titleElement.attr("href").takeIf { it.isNotBlank() } ?: return null
        
        // Cerca l'immagine in vari attributi
        val imgElement = this.selectFirst("img")
        val poster = when {
            imgElement?.attr("data-src").isNullOrBlank() -> imgElement?.attr("src")
            else -> imgElement?.attr("data-src")
        }
        
        // Cerca il rating
        val ratingElement = this.selectFirst(".rating, .imdb-rate, .rateIMDB, [class*='rate']")
        val ratingText = ratingElement?.text()?.replace("IMDb", "")?.replace("/10", "")?.trim()
        
        return newMovieSearchResponse(title, href) {
            this.posterUrl = fixUrlNull(poster)
            ratingText?.toFloatOrNull()?.let {
                this.score = Score(it, 10.0f)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?story=$query&do=search&subaction=search").document
        
        // Usa lo stesso selettore della main page per coerenza
        return doc.select("article, [class*='movie'], [class*='film']").mapNotNull {
            it.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        
        // Contenuto principale - cerca in più punti
        val content = doc.selectFirst("#dle-content, .single-content, article, main") ?: return null
        val title = content.select("h1, h2").firstOrNull()?.text()?.trim() ?: "Sconosciuto"
        val poster = fixUrlNull(content.select("img.wp-post-image, .poster img, img[src*='.jpg'], img[src*='.png']").firstOrNull()?.attr("src"))
        val plot = content.selectFirst("#sfull, .plot, .description, .entry-content")?.ownText()?.substringAfter("Trama: ")?.trim()
        val rating = content.selectFirst("span.rateIMDB, .imdb-rating, .rating")?.text()?.substringAfter("IMDb: ")?.trim()

        // Estrai generi e anno
        val details = content.select("#details > li, .details li, .metadata li")
        val genres = details.firstOrNull { it.text().contains("Genere:") }
            ?.select("a")?.map { it.text() } ?: emptyList()
        val year = details.firstOrNull { it.text().contains("Anno:") }
            ?.text()?.substringAfter("Anno:")?.trim()?.toIntOrNull()

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
            val mostraGuardaLink = doc.select("#player1 > iframe, iframe[src*='mostraguarda']").attr("src")
            val link = if (mostraGuardaLink.contains("mostraguarda")) {
                val mostraGuarda = app.get(mostraGuardaLink).document
                val mirrors = mostraGuarda.select("ul._player-mirrors > li, .player-mirrors li").mapNotNull {
                    val l = it.attr("data-link")
                    if (l.contains("mostraguarda")) null
                    else fixUrlNull(l)
                }
                mirrors
            } else {
                // Fallback: cerca iframe direttamente
                doc.select("iframe").mapNotNull { 
                    val src = it.attr("src")
                    if (src.isNotBlank() && !src.contains("mostraguarda")) fixUrlNull(src) else null 
                }
            }
            newMovieLoadResponse(title, url, TvType.Movie, link) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = genres
                this.year = year
                addScore(rating)
            }
        }
    }

    private fun getEpisodes(doc: Document, poster: String?): List<Episode> {
        val seasons = doc.selectFirst("div.tab-content, .seasons-container, .episodes-list")
            ?.select("div.tab-pane, .season") ?: return emptyList()

        return seasons.map { season ->
            val seasonNumber = season.attr("id").substringAfter("season-").toIntOrNull()
                ?: season.selectFirst("[data-season]")?.attr("data-season")?.toIntOrNull()
                ?: 1
            
            val episodes = season.select("li, .episode-item").map { ep ->
                val mirrors = ep.select("div.mirrors > a.mr, .mirrors a, .player-options a")
                    .mapNotNull { it.attr("data-link").takeIf { link -> link.isNotBlank() } }
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
        Log.d("Altadefinizione", "Links: $data")
        val links = parseJson<List<String>>(data)
        links.map {
            if (it.contains("dropload.tv")) {
                DroploadExtractor().getUrl(it, null, subtitleCallback, callback)
            } else {
                MySupervideoExtractor().getUrl(it, null, subtitleCallback, callback)
            }
        }
        return false
    }
}
