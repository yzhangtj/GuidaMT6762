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
        // Insecure fallbacks for network issues
        maven { 
            url = uri("http://repo.maven.apache.org/maven2")
            isAllowInsecureProtocol = true 
        }
        maven { 
            url = uri("http://plugins.gradle.org/m2/")
            isAllowInsecureProtocol = true
        }
    }
}

rootProject.name = "GuidaApp0606"
include(":app")
