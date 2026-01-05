package it.dogior.hadEnough

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class AltaDefinizione : MainAPI() {
    override var mainUrl = "https://altadefinizionez.click"
    override var name = "AltaDefinizione"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "it"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "$mainUrl/film/" to "Ultimi Aggiunti"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "${request.data}page/$page/"
        val doc = app.get(url).document
        
        val items = doc.select("article").mapNotNull { article ->
            val titleElement = article.selectFirst("h2 a, h3 a") ?: return@mapNotNull null
            val title = titleElement.text()
            val href = titleElement.attr("href")
            
            val poster = article.selectFirst("img")?.attr("src")
            
            newMovieSearchResponse(title, href) {
                this.posterUrl = poster ?: "" // CORREZIONE: non nullable
            }
        }
        
        return newHomePageResponse(
            list = HomePageList(request.name, items),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?story=$query&do=search&subaction=search").document
        return doc.select("article").mapNotNull { article ->
            val titleElement = article.selectFirst("h2 a, h3 a") ?: return@mapNotNull null
            val title = titleElement.text()
            val href = titleElement.attr("href")
            
            newMovieSearchResponse(title, href) {
                this.posterUrl = article.selectFirst("img")?.attr("src") ?: "" // CORREZIONE
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1, h2")?.text() ?: "Sconosciuto"
        val poster = doc.selectFirst("img.wp-post-image")?.attr("src") ?: "" // CORREZIONE
        val plot = doc.selectFirst("#sfull")?.text() ?: ""
        
        return newMovieLoadResponse(title, url, TvType.Movie, emptyList()) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return false
    }
}
