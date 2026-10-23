// Placeholder. Firebase AI Logic lands in a later phase. No google-services plugin here.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.jarvis.provider.gemini"
    compileSdk = 35
    defaultConfig { minSdk = 31 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
