// Placeholder. On-device Gemma lands in a later phase.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.jarvis.provider.local"
    compileSdk = 35
    defaultConfig { minSdk = 31 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
