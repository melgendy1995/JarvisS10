import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

val localProperties = Properties()
val localFile = rootProject.file("local.properties")
if (localFile.exists()) {
    localFile.inputStream().use { localProperties.load(it) }
}

// Proxy URL only. The Worker bearer stays in the Android Keystore, never in this file.
val groqProxyUrl = localProperties.getProperty("GROQ_PROXY_URL").orEmpty()
    .trim()
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "")
    .replace("\r", "")
    .replace("$", "\\$")

android {
    namespace = "com.jarvis.provider.groq"
    compileSdk = 35

    defaultConfig {
        minSdk = 31
        buildConfigField("String", "GROQ_PROXY_URL", "\"$groqProxyUrl\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.security.crypto)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
}
