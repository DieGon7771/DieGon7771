import org.jetbrains.kotlin.konan.properties.Properties

// use an integer for version numbers
version = 1

android {
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
    defaultConfig {
        val properties = Properties()
        
        // Cerca prima secrets.properties, poi local.properties
        val file = when {
            project.rootProject.file("secrets.properties").exists() -> 
                project.rootProject.file("secrets.properties")
            project.rootProject.file("local.properties").exists() -> 
                project.rootProject.file("local.properties")
            else -> null
        }
        
        if (file != null) {
            properties.load(file.inputStream())
        }
        
        val tmdbApi = properties.getProperty("TMDB_API", "")
        buildConfigField("String", "TMDB_API", "\"$tmdbApi\"")
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
