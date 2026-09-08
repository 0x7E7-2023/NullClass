pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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

rootProject.name = "NullClass"

include(":app")
include(":core:model")
include(":core:data")
include(":core:ui")
include(":feature:schedule")
include(":feature:edit")
include(":feature:settings")
include(":widget")
include(":importer")
include(":sync")
include(":ocr")
