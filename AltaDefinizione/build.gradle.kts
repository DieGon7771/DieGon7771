// use an integer for version numbers
version = 13


cloudstream {
    // All of these properties are optional, you can safely remove them

    description = "Film e SerieTV da Altadefinizione"
    authors = listOf("doGior","DieGon")

    /**
    * Status int as the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta only
    * */
    status = 1

    tvTypes = listOf("Movie", "TvSeries", "Documentary")

    requiresResources = false
    language = "it"

    iconUrl = "https://raw.githubusercontent.com/DieGon7771/ItaliaInStreaming/master/AltaDefinizione/altadefinizione_icon.png"
}
