package it.dogior.hadEnough

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.HttpUrl.Companion.toHttpUrl

class StreamingCommunityExtractor : ExtractorApi() {
    override val mainUrl = StreamingCommunity.mainUrl
    override val name = StreamingCommunity.name
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val TAG = "SC:GetUrl"
        Log.d(TAG, "REFERER: $referer  URL: $url")

        if (url.isBlank()) {
            Log.e(TAG, "❌ URL vuoto")
            return
        }

        // Carica pagina iframe-container e trova l'iframe vero
        val pageDoc = app.get(url).document
        val iframeSrcRaw = pageDoc.selectFirst("iframe")?.attr("src")
        if (iframeSrcRaw.isNullOrBlank()) {
            Log.e(TAG, "❌ Nessun iframe trovato in $url")
            return
        }

        val iframeUrl = resolveUrl(url, iframeSrcRaw)
        Log.d(TAG, "iframeUrl risolto: $iframeUrl")

        val playlistUrl = try {
            getPlaylistLink(iframeUrl)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Errore getPlaylistLink: ${e.message}")
            return
        }

        Log.w(TAG, "✅ FINAL URL: $playlistUrl")

        callback.invoke(
            newExtractorLink(
                source = "Vixcloud",
                name = "Streaming Community",
                url = playlistUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = referer ?: mainUrl
                this.quality = Qualities.Unknown.value
            }
        )
    }

    private fun resolveUrl(pageUrl: String, src: String): String {
        val base = pageUrl.toHttpUrl()
        return when {
            src.startsWith("http://") || src.startsWith("https://") -> src
            src.startsWith("//") -> "${base.scheme}:$src"
            src.startsWith("/") -> base.newBuilder().encodedPath(src).build().toString()
            else -> base.newBuilder().addPathSegments(src).build().toString()
        }
    }

    private suspend fun getPlaylistLink(iframeUrl: String): String {
        val TAG = "SC:getPlaylistLink"
        Log.d(TAG, "Item url: $iframeUrl")

        val script = fetchIframeHtml(iframeUrl)

        // Estrai il blocco dell'oggetto window.masterPlaylist con bilanciamento parentesi
        val masterBlock = extractJsObject(script, "window.masterPlaylist")
            ?: throw Exception("Nessun masterPlaylist trovato")
        // Ora estrai url/token/expires all'interno di quel blocco
        val url = Regex("""url\s*:\s*['"]([^'"]+)['"]""", RegexOption.DOT_MATCHES_ALL)
            .find(masterBlock)?.groupValues?.get(1)
            ?: throw Exception("URL non trovato nel masterPlaylist")

        val token = Regex("""token\s*:\s*['"]([^'"]+)['"]""", RegexOption.DOT_MATCHES_ALL)
            .find(masterBlock)?.groupValues?.get(1)
            ?: throw Exception("token non trovato nel masterPlaylist")

        val expires = Regex("""expires\s*:\s*['"]?(\d+)['"]?""", RegexOption.DOT_MATCHES_ALL)
            .find(masterBlock)?.groupValues?.get(1)
            ?: throw Exception("expires non trovato nel masterPlaylist")

        // canPlayFHD (true/false) fuori dall’oggetto
        val canPlayFHD = Regex("""window\.canPlayFHD\s*=\s*(true|false)""", RegexOption.DOT_MATCHES_ALL)
            .find(script)?.groupValues?.get(1)?.toBooleanStrictOrNull() ?: false

        var masterPlaylistUrl = if ("?b" in url) {
            "${url.replace("?b:1", "?b=1")}&token=$token&expires=$expires"
        } else {
            "$url?token=$token&expires=$expires"
        }
        if (canPlayFHD) {
            masterPlaylistUrl += "&h=1"
        }

        Log.d(TAG, "Master Playlist URL: $masterPlaylistUrl")
        return masterPlaylistUrl
    }

    private suspend fun fetchIframeHtml(iframeUrl: String): String {
        val headers = mutableMapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Host" to iframeUrl.toHttpUrl().host,
            "Referer" to mainUrl,
            "Sec-Fetch-Dest" to "iframe",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "cross-site",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:131.0) Gecko/20100101 Firefox/131.0",
        )
        val iframeDoc = app.get(iframeUrl, headers = headers).document
        // Unisci tutti gli script in un’unica stringa JS
        return iframeDoc.select("script").joinToString("\n") { it.data() }
    }

    /**
     * Estrae l'oggetto JS a partire da una dichiarazione tipo:
     *   window.masterPlaylist = { ... };
     * usando il bilanciamento delle parentesi graffe per evitare errori.
     */
    private fun extractJsObject(script: String, varName: String): String? {
        val startIndex = script.indexOf(varName)
        if (startIndex == -1) return null

        // trova il primo '{' dopo il varName
        val braceStart = script.indexOf('{', startIndex)
        if (braceStart == -1) return null

        var depth = 0
        var i = braceStart
        while (i < script.length) {
            val c = script[i]
            if (c == '{') depth++
            if (c == '}') {
                depth--
                if (depth == 0) {
                    // include le graffe
                    return script.substring(braceStart, i + 1)
                }
            }
            i++
        }
        return null
    }
}
