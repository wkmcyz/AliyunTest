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
        maven(url = "https://maven.zhenguanyu.com/content/repositories/releases")

        maven(url = "https://storage.googleapis.com/r8-releases/raw")
        maven(url = "https://maven.aliyun.com/repository/public")
        maven(url = "https://maven.aliyun.com/repository/gradle-plugin")
        maven(url = "https://mirrors.huaweicloud.com/repository/maven")
        maven(url = "https://developer.hihonor.com/repo")
        maven(url = "https://storage.flutter-io.cn/download.flutter.io")
    }
}

rootProject.name = "AliyunTest"
include(":app")
 