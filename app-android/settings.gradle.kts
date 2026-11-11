
import java.io.File
import java.util.Properties

// GitHub Packages credentials.
// Local credentials can be stored outside the checkout.
// CI reads GH_PACKAGES_USER and GH_PACKAGES_TOKEN from environment variables.
val githubPropertiesFile = File(
    System.getProperty("user.home"),
    ".config/twidget/github.properties",
)

val githubProperties = Properties().apply {
    githubPropertiesFile.takeIf { it.isFile }?.inputStream()?.use { load(it) }
}

val ghPackagesUser: String =
    githubProperties.getProperty("ghUsername")
        ?: System.getenv("GH_PACKAGES_USER")
        ?: ""

val ghPackagesToken: String =
    githubProperties.getProperty("ghAccessToken")
        ?: System.getenv("GH_PACKAGES_TOKEN")
        ?: ""

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

        // One UI design library.
        maven("https://maven.pkg.github.com/tribalfs/oneui-design") {
            credentials {
                username = ghPackagesUser
                password = ghPackagesToken
            }
        }

        // SESL AndroidX libraries.
        maven("https://maven.pkg.github.com/tribalfs/sesl-androidx") {
            credentials {
                username = ghPackagesUser
                password = ghPackagesToken
            }
        }

        // SESL Material Components.
        maven("https://maven.pkg.github.com/tribalfs/sesl-material-components-android") {
            credentials {
                username = ghPackagesUser
                password = ghPackagesToken
            }
        }

        maven("https://jitpack.io")
    }
}

rootProject.name = "OneOS"

include(":app")
include(":guest")
include(":core:model")
include(":core:device")
include(":core:root")
include(":core:safety")
