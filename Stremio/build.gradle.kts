import org.jetbrains.kotlin.konan.properties.Properties

version = 1

android {
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
    defaultConfig {
        val properties = Properties()
        properties.load(project.rootProject.file("secrets.properties").inputStream())
        android.buildFeatures.buildConfig = true
        buildConfigField("String", "TMDB_API", "\"${properties.getProperty("TMDB_API")}\"")
    }
}

dependencies {
    implementation("com.google.android.material:material:1.13.0")
}

cloudstream {
    language = "it"
    description = "ATTUALMENTE IN FASE BETA\n\n[!] Configurazione Richiesta\n- StremioX: per utilizzare addons di streaming\n- StremioC: per utilizzare addons di catalogo"
    authors = listOf("Hexated,phisher98,DieGon")
    status = 3
    tvTypes = listOf(
        "TvSeries",
        "Movie",
    )
    requiresResources = true
    iconUrl = "https://raw.githubusercontent.com/DieGon7771/ItaliaInStreaming/master/Stremio/stremio_icon.png"
}
