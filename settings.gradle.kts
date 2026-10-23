pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "JarvisS10"

include(
    ":app",
    ":core",
    ":orb",
    ":audio",
    ":data",
    ":provider-gemini",
    ":provider-groq",
    ":provider-local",
    ":channel-telegram",
)
