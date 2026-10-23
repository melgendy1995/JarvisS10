// Placeholder. Groq backup lands in a later phase. No API key belongs in this module.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.jarvis.provider.groq"
    compileSdk = 35
    defaultConfig { minSdk = 31 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
