// use an integer for version numbers
version = 3


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
    status = 0

    tvTypes = listOf("Movie", "TvSeries", "Documentary")

    requiresResources = false
    language = "it"

    iconUrl = "https://altadefinizionegratis.art/templates/Dark/img/favicon.ico"
}
