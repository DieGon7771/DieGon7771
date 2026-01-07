// use an integer for version numbers
version = 17


cloudstream {
    // All of these properties are optional, you can safely remove them

    description = "Film e SerieTV da CB01"
    authors = listOf("doGior","DieGon")

    /**
    * Status int as the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta only
    * */
    status = 1

    tvTypes = listOf("Movie", "TvSeries", "Cartoon")

    requiresResources = false
    language = "it"

    iconUrl = "https://raw.githubusercontent.com/DieGon7771/ItaliaInStreaming/master/CB01/cb01_icon.png"
}
dependencies{
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
}
