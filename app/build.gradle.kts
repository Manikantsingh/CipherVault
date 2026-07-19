plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val releaseKeystorePath = providers.environmentVariable("ANDROID_KEYSTORE_PATH")
val releaseKeystorePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD")
val releaseKeyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS")
val releaseKeyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD")

android {
    namespace = "com.example.ciphervault"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.ciphervault"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = releaseKeystorePath.orNull?.let(::file)
            storePassword = releaseKeystorePassword.orNull
            keyAlias = releaseKeyAlias.orNull
            keyPassword = releaseKeyPassword.orNull
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-dev"

            val webClientId = providers.gradleProperty("DEV_WEB_CLIENT_ID")
                .orElse(providers.gradleProperty("WEB_CLIENT_ID"))
                .orElse("YOUR_DEV_WEB_CLIENT_ID.apps.googleusercontent.com")
            buildConfigField("String", "WEB_CLIENT_ID", "\"${webClientId.get()}\"")
            buildConfigField("String", "ENVIRONMENT", "\"development\"")
            resValue("string", "app_name", "CipherVault Dev")
        }

        release {
            signingConfig = signingConfigs.getByName("release")

            val webClientId = providers.gradleProperty("PROD_WEB_CLIENT_ID")
                .orElse("MISSING_PROD_WEB_CLIENT_ID")
            buildConfigField("String", "WEB_CLIENT_ID", "\"${webClientId.get()}\"")
            buildConfigField("String", "ENVIRONMENT", "\"production\"")
            resValue("string", "app_name", "CipherVault")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

val validateProductionConfiguration = tasks.register("validateProductionConfiguration") {
    val productionClientId = providers.gradleProperty("PROD_WEB_CLIENT_ID")
    doLast {
        val clientId = productionClientId.orNull
        check(!clientId.isNullOrBlank() && clientId.endsWith(".apps.googleusercontent.com")) {
            "PROD_WEB_CLIENT_ID must be set to the production Web OAuth client ID."
        }
    }
}

val validateReleaseSigningConfiguration = tasks.register("validateReleaseSigningConfiguration") {
    doLast {
        val keystore = releaseKeystorePath.orNull?.let(::file)
        check(keystore?.isFile == true) {
            "ANDROID_KEYSTORE_PATH must point to the release upload keystore."
        }
        check(!releaseKeystorePassword.orNull.isNullOrBlank()) {
            "ANDROID_KEYSTORE_PASSWORD must be set for release builds."
        }
        check(!releaseKeyAlias.orNull.isNullOrBlank()) {
            "ANDROID_KEY_ALIAS must be set for release builds."
        }
        check(!releaseKeyPassword.orNull.isNullOrBlank()) {
            "ANDROID_KEY_PASSWORD must be set for release builds."
        }
    }
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(validateProductionConfiguration, validateReleaseSigningConfiguration)
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.work.runtime)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    implementation(libs.google.id)
    implementation(libs.play.services.auth)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.play.services)
    implementation(libs.serialization.json)
    implementation(libs.okhttp)

    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
}