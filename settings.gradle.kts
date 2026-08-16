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
        mavenCentral {
            // NewPipeExtractor n'est PAS vraiment publié sur Central (juste indexé/mirroré
            // par endroits) -> le laisser essayer ici peut planter sur un 429 au lieu de
            // basculer proprement sur jitpack. On l'exclut explicitement de ce repo.
            content { excludeGroup("com.github.TeamNewPipe") }
        }
        maven("https://jitpack.io") {
            // Restreint jitpack à ce dont on a besoin dessus, pour ne pas ralentir/polluer
            // la résolution des autres dépendances (AndroidX, etc.)
            content { includeGroup("com.github.TeamNewPipe") }
        }
    }
}

rootProject.name = "NeoBelieve"
include(":app")
