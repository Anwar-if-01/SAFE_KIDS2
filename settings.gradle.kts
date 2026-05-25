
pluginManagement {
    repositories {
        // Tencent Cloud mirror - use these for faster downloads (Aliyun is currently returning 502)
        maven { url = uri("https://mirrors.tencent.com/nexus/repository/maven-public/") }
        
        // Repositories where Gradle should look for plugins
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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Tencent Cloud mirror - use these for faster downloads (Aliyun is currently returning 502)
        maven { url = uri("https://mirrors.tencent.com/nexus/repository/maven-public/") }
        
        // Repositories where Gradle should look for your app's libraries
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "SAFE_KIDS2" // Your project name
include(":app")
    