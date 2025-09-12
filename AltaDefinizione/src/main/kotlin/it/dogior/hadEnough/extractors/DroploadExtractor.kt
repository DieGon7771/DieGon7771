package it.dogior.hadEnough.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.api.Log
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink

class DroploadExtractor : ExtractorApi() {
    override var name = "Dropload"
    override var mainUrl = "https://dropload.tv"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.i("DroploadExtractor", "🔎 Trying to extract: $url")

        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
            "Accept" to "*/*",
            "Connection" to "keep-alive"
        )

        try {
            val response = app.get(url, headers = headers)
            val body = response.body.string()
            Log.i("DroploadExtractor", "✅ Page loaded, body length: ${body.length}")

            val evalRegex = Regex("""eval\(function\(p,a,c,k,e,(?:r|d).*?\n""")
            val evalBlock = evalRegex.find(body)?.value ?: run {
                Log.e("DroploadExtractor", "❌ No eval() block found.")
                return
            }

            var unpacked = evalBlock
            var videoUrl: String? = null

            for (i in 1..5) {
                Log.i("DroploadExtractor", "▶ Unpacking pass $i...")
                unpacked = getAndUnpack(unpacked)
                Log.i("DroploadExtractor", "✅ Unpacked pass $i, size: ${unpacked.length}")
                Log.d("DroploadExtractor", "Content (pass $i): ${unpacked.take(400)}...")

                // Cerca il file JWPlayer
                videoUrl = Regex("""file\s*:\s*"([^"]+\.m3u8[^"]*)"""")
                    .find(unpacked)?.groupValues?.get(1)

                if (!videoUrl.isNullOrEmpty()) break
            }

            if (videoUrl.isNullOrEmpty()) {
                Log.e("DroploadExtractor", "❌ Failed to extract video URL after 5 passes.")
                return
            }

            Log.i("DroploadExtractor", "✅ Extracted video URL: $videoUrl")

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "Dropload",
                    url = videoUrl,
                    type = ExtractorLinkType.M3U8
                ){
                    this.referer = referer ?: ""
                    quality = Qualities.Unknown.value
                }
            )
        } catch (e: Exception) {
            Log.e("DroploadExtractor", "❌ Error during extraction: ${e.message}")
        }
    }
}
