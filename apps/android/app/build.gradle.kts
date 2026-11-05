import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Optional release signing. Create `apps/android/keystore.properties` (gitignored)
// with storeFile / storePassword / keyAlias / keyPassword to sign
// release builds locally; CI injects the same file from secrets. Without it,
// release builds stay unsigned.
val keystoreProperties = Properties().apply {
    val keystoreFile = rootProject.file("keystore.properties")
    if (keystoreFile.exists()) {
        keystoreFile.inputStream().use { load(it) }
    }
}
val hasReleaseSigning = keystoreProperties.getProperty("storeFile") != null

// Ship the project's license/notice files with every APK, not only in Git.
val licenseAssets = layout.buildDirectory.dir("generated/licenseAssets")
val generateLicenseAssets by tasks.registering(Sync::class) {
    from(rootProject.projectDir.resolve("../..")) {
        include("LICENSE", "NOTICE", "THIRD_PARTY_NOTICES.md")
        into("licenses")
    }
    into(licenseAssets)
}

android {
    namespace = "com.dropflow.android"
    sourceSets.getByName("main").assets.srcDir(licenseAssets)
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dropflow.android"
        minSdk = 26
        targetSdk = 34
        versionCode = 19
        versionName = "0.2.0-alpha.2"

        testInstrumentationRunner = providers.gradleProperty("dropflowInteropRunner")
            .orElse("androidx.test.runner.AndroidJUnitRunner").get()
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                // storeFile is resolved against the root project (apps/android/),
                // matching where CI and local setup place release.keystore next
                // to keystore.properties. file() here would resolve against the
                // :app module directory and miss the keystore.
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

tasks.named("preBuild").configure { dependsOn(generateLicenseAssets) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.ui.tooling)
    testImplementation("junit:junit:4.13.2")
}
