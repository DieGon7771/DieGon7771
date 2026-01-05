// use an integer for version numbers
version = 26


cloudstream {
    // All of these properties are optional, you can safely remove them

    description = "Film e SerieTV da StreamingCommunity"
    authors = listOf("doGior","DieGon")

    /**
    * Status int as the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta only
    * */
    status = 2
    tvTypes = listOf(
        "TvSeries",
        "Movie",
        "Documentary",
        "Cartoon"
    )


    requiresResources = false
    language = "it"

    iconUrl = "https://streamingunity.to/apple-touch-icon.png?v=2"
}
