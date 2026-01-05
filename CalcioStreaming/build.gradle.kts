// use an integer for version numbers
version = 11


cloudstream {
    language = "it"
    // All of these properties are optional, you can safely remove them

     description = "Live streams da CalcioStreaming"
    authors = listOf("doGior","DieGon")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified
    tvTypes = listOf(
        "Live",
    )

    iconUrl = "https://www.calciostreaming.cool/templates/calciostreaming1/images/icons/apple-touch-icon.png"
}
