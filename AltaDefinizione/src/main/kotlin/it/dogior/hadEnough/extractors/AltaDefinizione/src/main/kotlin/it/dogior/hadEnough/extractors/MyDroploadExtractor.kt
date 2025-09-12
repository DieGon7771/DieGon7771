package it.dogior.hadEnough.extractors

import com.lagradost.cloudstream3.extractors.Supervideo

class MySupervideoExtractor : Supervideo() {
    override var mainUrl = "supervideo.cc"
}
