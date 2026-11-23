plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// index.html at the repo root is the single source of truth. It is copied into
// a generated assets folder at build time instead of being duplicated here.
val webAssetsDir = layout.buildDirectory.dir("generated/webAssets")

val copyWebAssets by tasks.registering(Copy::class) {
    from(rootProject.file("../index.html"))
    into(webAssetsDir)
}

android {
    namespace = "com.projectmenu.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.projectmenu.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    sourceSets {
        getByName("main") {
            assets.srcDir(webAssetsDir)
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

tasks.named("preBuild") { dependsOn(copyWebAssets) }
// Make every asset-merging task depend on the copy so Gradle never warns about
// implicit dependencies on the generated folder.
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }
    .configureEach { dependsOn(copyWebAssets) }

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.2")
}
