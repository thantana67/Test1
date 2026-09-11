dependencies {
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}

// Use an integer for version numbers
version = 1

cloudstream {
    description = "Public domain movies from Internet Archive"
    authors = listOf("thantana67")
    status = 1
    tvTypes = listOf("Movie")
    requiresResources = false  // เพราะไม่ได้ใช้ BlankFragment/UI พิเศษ
    language = "en"
    iconUrl = "https://archive.org/favicon.ico"
}

android {
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}