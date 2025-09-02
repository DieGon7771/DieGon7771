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
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.LoadResponse.Companion.addRating
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.loadExtractor
import okhttp3.FormBody
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class AltaDefinizione : MainAPI() {
    override var mainUrl = "https://altadefinizionegratis.center"
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
        val items = doc.select("#dle-content > .col").mapNotNull {
            it.toSearchResponse()
        }
        val pagination = doc.select("div.pagin > a").last()?.text()?.toIntOrNull()
        val hasNext = page < (pagination ?: 0)

        return newHomePageResponse(HomePageList(request.name, items), hasNext = hasNext)
    }

    private fun Element.toSearchResponse(): MovieSearchResponse? {
        val aTag = this.selectFirst(".movie-poster > a") ?: return null
        val img = aTag.selectFirst("img")?.attr("data-src") ?: aTag.selectFirst("img")?.attr("src")
        val title = this.select(".movie-title > a").text().trim()
        val href = aTag.attr("href")
        val poster = fixUrlNull(img)
//        val rating = this.selectFirst("span.rate")?.text()
        return newMovieSearchResponse(title, href) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val requestBody = formRequestBody(query)
        val doc = app.post(
            "$mainUrl/",
            requestBody = requestBody,
            headers = mapOf(
                "Content-Type" to "application/x-www-form-urlencoded",
                "Content-Length" to requestBody.contentLength().toString()
            )
        ).document

        return doc.select("div.movie").mapNotNull {
            it.toSearchResponse()
        }
    }

    private fun formRequestBody(query: String): FormBody {
        return FormBody.Builder()
            .addEncoded("story", query)
            .addEncoded("do", "search")
            .addEncoded("subaction", "search")
            .build()
    }


    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val info = doc.selectFirst("div.row.align-items-start")!!
        val poster = fixUrlNull(info.select("img.movie_entry-poster").attr("data-src"))
        val plot = info.selectFirst("#text-content")?.ownText()?.trim() +
                info.selectFirst(".more-text")?.ownText()?.trim()
        val title = info.select("h1.movie_entry-title").text().ifEmpty { "Sconosciuto" }
        val duration = info.select("div.meta-list > span").last()?.text()
        val rating = doc.select("span.label.imdb").text()

        val details = info.select(".movie_entry-details").select("div.row.flex-nowrap.mb-2")
        val genreElements = details.toList().first { it.text().contains("Genere: ") }
        val genres = genreElements.select("a").map { it.text() }
        val yearElements = details.toList().first { it.text().contains("Anno: ") }
        val year = yearElements.select("div").last()?.text()
        val episodes = getEpisodes(doc)
        return if (episodes.size > 1) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = genres
                addRating(rating)
            }
        } else {
            val rows = doc.select("iframe").first()
            val mostraGuardaLink = rows?.attr("src")
            val link = if (mostraGuardaLink?.contains("mostraguarda") == true) {
                val mostraGuarda = app.get(mostraGuardaLink).document
                val mirrors = mostraGuarda.select("ul._player-mirrors > li").mapNotNull {
                    val l = it.attr("data-link")
                    if (l.contains("mostraguarda")) null
                    else fixUrlNull(l)
                }
                mirrors
            } else {
                emptyList()
            }
            newMovieLoadResponse(title, url, TvType.Movie, link) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = genres
                this.year = year?.toIntOrNull()
                addRating(rating)
                addDuration(duration)
            }
        }
    }

    private fun getEpisodes(doc: Document): List<Episode> {
        val episodeElements = doc.select(".series-select > .dropdown.mirrors")
        return episodeElements.map {
            val season = it.attr("data-season")
            val episode = it.attr("data-episode").substringAfter("-")
            val mirrors = it.select(".dropdown-menu > span").map { it.attr("data-link") }
            newEpisode(mirrors) {
                this.season = season.toIntOrNull()
                this.episode = episode.toIntOrNull()
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val links = parseJson<List<String>>(data)
        links.map {
            loadExtractor(it, subtitleCallback, callback)
        }
        return false
    }

//    data class VideoStream(
//        val host: String,
//        val url: String
//    )
}
