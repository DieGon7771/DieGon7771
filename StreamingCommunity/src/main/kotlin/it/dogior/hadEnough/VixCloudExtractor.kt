package it.dogior.hadEnough

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject

class VixCloudExtractor : ExtractorApi() {
    override val mainUrl = "vixcloud.co"
    override val name = "VixCloud"
    override val requiresReferer = false
    val TAG = "VixCloudExtractor"
    
    // Headers come val (non mutableMap)
    private val headers = mapOf(
        "Accept" to "*/*",
        "Connection" to "keep-alive",
        "Cache-Control" to "no-cache",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:131.0) Gecko/20100101 Firefox/131.0",
        "Referer" to "https://streamingunity.so/",
        "Origin" to "https://streamingunity.so",
        "Accept-Encoding" to "identity"
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d(TAG, "REFERER: $referer  URL: $url")
        
        val playlistUrl = getPlaylistLink(url)
        Log.w(TAG, "Master Playlist URL: $playlistUrl")
        
        // Prova a ottenere la playlist specifica per il video
        val finalPlaylistUrl = getVideoPlaylistUrl(playlistUrl)
        
        callback.invoke(
            newExtractorLink(
                source = "VixCloud",
                name = "Streaming Community - 1080p",
                url = finalPlaylistUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.headers.putAll(headers) // Usa putAll invece di assegnazione
                // Rimuovi quality se dà problemi, non è essenziale
                this.isM3u8 = true
            }
        )
        
        // Aggiungi anche un link di fallback per compatibilità
        callback.invoke(
            newExtractorLink(
                source = "VixCloud-Direct",
                name = "SC - Direct Video",
                url = playlistUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                this.headers.putAll(headers)
                this.isM3u8 = true
            }
        )
    }

    private suspend fun getVideoPlaylistUrl(masterUrl: String): String {
        return try {
            // Scarica la master playlist
            val response = app.get(masterUrl, headers = headers, timeout = 30)
            if (!response.isSuccessful) {
                Log.e(TAG, "Errore scaricamento master playlist: ${response.code}")
                return masterUrl
            }
            
            val content = response.text
            Log.d(TAG, "Master playlist (prime 20 righe):")
            content.lines().take(20).forEach { Log.d(TAG, it) }
            
            // Analizza la playlist per trovare la qualità migliore
            val lines = content.lines()
            var bestQualityUrl: String? = null
            var bestQuality = -1
            
            for (i in lines.indices) {
                val line = lines[i]
                
                // Cerca righe con risoluzione
                if (line.contains("RESOLUTION=")) {
                    // Estrai risoluzione
                    val resolutionMatch = Regex("RESOLUTION=(\\d+)x(\\d+)").find(line)
                    val width = resolutionMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    
                    // Controlla la riga successiva per l'URL
                    val nextLine = lines.getOrNull(i + 1)
                    if (nextLine != null && 
                        (nextLine.endsWith(".m3u8") || nextLine.contains(".m3u8?")) && 
                        !nextLine.startsWith("#")) {
                        
                        // Seleziona la risoluzione più alta
                        if (width > bestQuality) {
                            bestQuality = width
                            bestQualityUrl = nextLine
                        }
                    }
                }
            }
            
            // Se trovata una playlist specifica
            if (bestQualityUrl != null) {
                // Costruisci URL completo
                val baseUrl = masterUrl.substringBeforeLast("/") + "/"
                val fullUrl = if (bestQualityUrl.startsWith("http")) {
                    bestQualityUrl
                } else {
                    baseUrl + bestQualityUrl
                }
                
                Log.d(TAG, "Trovata playlist qualità: ${bestQuality}p - URL: $fullUrl")
                return fullUrl
            }
            
            // Se non trovato, usa la master
            Log.d(TAG, "Nessuna playlist specifica trovata, uso master")
            masterUrl
            
        } catch (e: Exception) {
            Log.e(TAG, "Errore ottenendo video playlist: ${e.message}")
            masterUrl // Fallback alla master
        }
    }

    private suspend fun getPlaylistLink(url: String): String {
        Log.d(TAG, "Item url: $url")

        val script = getScript(url)
        val masterPlaylist = script.getJSONObject("masterPlaylist")
        val masterPlaylistParams = masterPlaylist.getJSONObject("params")
        val token = masterPlaylistParams.getString("token")
        val expires = masterPlaylistParams.getString("expires")
        val playlistUrl = masterPlaylist.getString("url")

        var masterPlaylistUrl: String
        val params = "token=${token}&expires=${expires}"
        masterPlaylistUrl = if ("?b" in playlistUrl) {
            "${playlistUrl.replace("?b:1", "?b=1")}&$params"
        } else {
            "${playlistUrl}?$params"
        }
        Log.d(TAG, "masterPlaylistUrl: $masterPlaylistUrl")

        if (script.getBoolean("canPlayFHD")) {
            masterPlaylistUrl += "&h=1"
        }

        Log.d(TAG, "Master Playlist URL: $masterPlaylistUrl")
        return masterPlaylistUrl
    }

    private suspend fun getScript(url: String): JSONObject {
        Log.d(TAG, "Item url: $url")

        val iframe = app.get(url, headers = headers, interceptor = CloudflareKiller()).document
        Log.d(TAG, "Iframe ottenuto")

        val scripts = iframe.select("script")
        val script =
            scripts.find { it.data().contains("masterPlaylist") }?.data()?.replace("\n", "\t")
                ?: throw Error("Script non trovato")

        val scriptJson = getSanitisedScript(script)
        Log.d(TAG, "Script Json ottenuto")
        return JSONObject(scriptJson)
    }

    private fun getSanitisedScript(script: String): String {
        // Split by top-level assignments like window.xxx =
        val parts = Regex("""window\.(\w+)\s*=""")
            .split(script)
            .drop(1) // first split part is empty before first assignment

        val keys = Regex("""window\.(\w+)\s*=""")
            .findAll(script)
            .map { it.groupValues[1] }
            .toList()

        val jsonObjects = keys.zip(parts).map { (key, value) ->
            // Clean up the value
            val cleaned = value
                .replace(";", "")
                // Quote keys only inside objects
                .replace(Regex("""(\{|\[|,)\s*(\w+)\s*:"""), "$1 \"$2\":")
                // Remove trailing commas before } or ]
                .replace(Regex(""",(\s*[}\]])"""), "$1")
                .trim()

            "\"$key\": $cleaned"
        }
        val finalObject =
            "{\n${jsonObjects.joinToString(",\n")}\n}"
                .replace("'", "\"")

        return finalObject
    }
}
