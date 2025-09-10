package it.dogior.hadEnough

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class StreamingCommunityPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(StreamingCommunity())
        registerExtractorAPI(VixCloudExtractor())
        registerExtractorAPI(VixSrcExtractor())
    }
}
