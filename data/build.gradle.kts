// Placeholder. Room memory lands in a later phase.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.jarvis.data"
    compileSdk = 35
    defaultConfig { minSdk = 31 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
