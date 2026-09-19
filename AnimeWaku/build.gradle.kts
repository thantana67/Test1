dependencies {
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}

// Use an integer for version numbersadads
version = 48

cloudstream {
    description = "ANime wiTH THAI SUBS"
    authors = listOf("thantana67")
    status = 1
    tvTypes = listOf("Anime")
    requiresResources = false  // เพราะไม่ได้ใช้ BlankFragment/UI พิเศษ s
    language = "en"
    iconUrl = "https://archive.org/favicon.ico"
}

android {
    namespace = "com.example"
}

// git add AnimeWaku/build.gradle.kts
// git add AnimeWaku/src/main/kotlin/com/example/AnimeWakuProvider.kt
// git commit -m "solve test"