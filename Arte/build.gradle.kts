// use an integer for version numbers
version = 2


cloudstream {
    // All of these properties are optional, you can safely remove them

    description = "Documentari da arte.tv"
    authors = listOf("doGior","DieGon")

    /**
    * Status int as the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta only
    * */
    status = 1

    tvTypes = listOf("Documentary")

    requiresResources = false
    language = "it"

    iconUrl = "https://static-cdn.arte.tv/replay/favicons/favicon-194x194.png"
}
