package it.dogior.hadEnough

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import it.dogior.hadEnough.extractors.DroploadExtractor

@CloudstreamPlugin
class AltaDefinizionePlugin: Plugin() {
    override fun load(context: Context) {
        // All providers should be added in this manner
        registerMainAPI(AltaDefinizione())
        registerExtractorAPI(DroploadExtractor())
    }
}
