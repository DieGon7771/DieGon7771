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

    // default headers più "umani" per ridurre il rischio di challenge immediata
    private fun defaultHeaders(referer: String? = null): Map<String, String> {
        val base = mutableMapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
            "Accept-Language" to "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7",
            "Connection" to "keep-alive",
            "Referer" to (referer ?: mainUrl)
        )
        return base
    }

    // safeGet: ottiene la pagina e controlla se sembra un challenge/captcha
    private suspend fun safeGet(url: String, referer: String? = null, timeout: Int = 15_000): Document? {
        try {
            val resp = app.get(url, headers = defaultHeaders(referer), timeout = timeout)
            val body = resp.body.string()
            // controllo semplificato per rilevare challenge/captcha
            val lower = body.lowercase()
            if (lower.contains("checking your browser") ||
                lower.contains("cf-chl-bypass") ||
                lower.contains("please enable javascript") ||
                lower.contains("captcha") ||
                lower.contains("recaptcha") ||
                lower.contains("cloudflare")
            ) {
                Log.e("AltaDefinizione", "Rilevata challenge/captcha su: $url")
                return null
            }
            return org.jsoup.Jsoup.parse(body)
        } catch (e: Exception) {
            Log.e("AltaDefinizione", "safeGet error: ${e.message}")
            return null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "${request.data}page/$page/"
        val doc = safeGet(url) ?: throw Exception("Pagina bloccata o non raggiungibile (captcha/cloudflare).")
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
        return newMovieSearchResponse(title, href) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val requestBody = formRequestBody(query)
        try {
            val resp = app.post(
                "$mainUrl/",
                requestBody = requestBody,
                headers = defaultHeaders(mainUrl)
            )
            val body = resp.body.string()
            // controllo captcha nella risposta
            val lower = body.lowercase()
            if (lower.contains("captcha") || lower.contains("checking your browser")) {
                Log.e("AltaDefinizione", "search blocked by captcha")
                return emptyList()
            }
            val doc = org.jsoup.Jsoup.parse(body)
            return doc.select("div.movie").mapNotNull { it.toSearchResponse() }
        } catch (e: Exception) {
            Log.e("AltaDefinizione", "search error: ${e.message}")
            return emptyList()
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
        val doc = safeGet(url) ?: throw Exception("Pagina bloccata o non raggiungibile (captcha/cloudflare).")
        val info = doc.selectFirst("div.row.align-items-start")!!
        val poster = fixUrlNull(info.select("img.movie_entry-poster")?.attr("data-src"))
        val plot = (info.selectFirst("#text-content")?.ownText()?.trim().orEmpty() +
                info.selectFirst(".more-text")?.ownText()?.trim().orEmpty()).trim()
        val title = info.select("h1.movie_entry-title").text().ifEmpty { "Sconosciuto" }
        val duration = info.select("div.meta-list > span").last()?.text()
        val rating = doc.select("span.label.imdb").text()

        val details = info.select(".movie_entry-details").select("div.row.flex-nowrap.mb-2")
        val genreElements = details.firstOrNull { it.text().contains("Genere: ") }
        val genres = genreElements?.select("a")?.map { it.text() } ?: emptyList()
        val yearElements = details.firstOrNull { it.text().contains("Anno: ") }
        val year = yearElements?.select("div")?.last()?.text()
        val episodes = getEpisodes(doc)

        return if (episodes.size > 1) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = genres
                addRating(rating)
            }
        } else {
            val iframe = doc.selectFirst("iframe")
            val mostraGuardaLink = iframe?.attr("src")
            val linkList = if (mostraGuardaLink?.contains("mostraguarda") == true) {
                val mostraDoc = safeGet(mostraGuardaLink, referer = url)
                val mirrors = mostraDoc?.select("ul._player-mirrors > li")?.mapNotNull {
                    val l = it.attr("data-link")
                    if (l.contains("mostraguarda")) null else fixUrlNull(l)
                } ?: emptyList()
                mirrors
            } else {
                emptyList()
            }

            newMovieLoadResponse(title, url, TvType.Movie, linkList) {
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
            val mirrors = it.select(".dropdown-menu > span").mapNotNull { it.attr("data-link") }
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
        val links = try {
            parseJson<List<String>>(data)
        } catch (e: Exception) {
            Log.e("AltaDefinizione", "parseJson links error: ${e.message}")
            return false
        }

        var foundAny = false

        links.forEach { raw ->
            val l = raw ?: return@forEach
            // prima verifichiamo se la pagina è raggiungibile (no captcha)
            val testDoc = safeGet(l)
            if (testDoc == null) {
                Log.e("AltaDefinizione", "Link bloccato o challenge rilevata, skipping: $l")
                return@forEach
            }

            try {
                // invoca gli estrattori disponibili tramite utilità loadExtractor
                // alcuni estrattori richiedono anche la referer: qui passiamo il link corrente
                loadExtractor(l, l, subtitleCallback, callback)
                foundAny = true
            } catch (e: Exception) {
                Log.e("AltaDefinizione", "Errore estrazione per $l : ${e.message}")
            }
        }

        return foundAny
    }
}
