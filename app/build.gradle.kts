plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.kanatron.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kanatron.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core:1.17.0")
    implementation("androidx.activity:activity-compose:1.13.0")

    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
}

configurations.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group?.startsWith("androidx.compose.") == true && requested.version == "1.12.1") {
            useVersion("1.11.4")
            because("Compose 1.12.1 requires compileSdk 37; this app is fixed at compileSdk 36")
        }
    }
}
