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
        // Xposed API (compile-only) for the LSPosed System UI hooks
        maven("https://api.xposed.info/")
    }
}

rootProject.name = "TruckControllerPro"
include(":app")
