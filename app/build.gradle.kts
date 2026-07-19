plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.example.ciphervault"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.ciphervault"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        val webClientId = providers.gradleProperty("WEB_CLIENT_ID")
            .orElse("YOUR_WEB_CLIENT_ID.apps.googleusercontent.com")
        buildConfigField("String", "WEB_CLIENT_ID", "\"${webClientId.get()}\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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