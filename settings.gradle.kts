pluginManagement {
    repositories {
        google()          // ✅ مهم جداً
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()          // ✅ مهم جداً
        mavenCentral()
    }
}

rootProject.name = "Anime4up"
include(":app")
