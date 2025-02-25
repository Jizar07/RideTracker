import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)

}

android {
    namespace = "com.stoffeltech.ridetracker"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.stoffeltech.ridetracker"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        renderscriptTargetApi = 21
        renderscriptSupportModeEnabled = true

        // Manually load local.properties to get the API key
        val localProperties = Properties()
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            localProperties.load(FileInputStream(localPropertiesFile))
        }

        // Retrieve the API key from local.properties
        val googlePlacesApiKey = localProperties.getProperty("GOOGLE_PLACES_API_KEY") ?: ""
        val googleAIApiKey = localProperties.getProperty("GOOGLE_AI_API_KEY") ?: ""

        buildConfigField("String", "GOOGLE_AI_API_KEY", "\"${googleAIApiKey}\"")
        buildConfigField("String", "GOOGLE_PLACES_API_KEY", "\"$googlePlacesApiKey\"")

        manifestPlaceholders["googleMapsKey"] = googlePlacesApiKey

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {

    // Google Maps dependency to display a map
    implementation("com.google.android.gms:play-services-maps:19.0.0")

    // Add Google Play Services Location dependency (ensure you sync your project after adding)
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // Google Places SDK dependency providing access to POI search functionality.
    implementation("com.google.android.libraries.places:places:4.1.0")

    implementation("com.squareup.okhttp3:okhttp:4.9.1")

    implementation("com.google.ai.client.generativeai:generativeai:0.9.0")


    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")
    implementation("com.google.mlkit:camera:16.0.0-beta3")

    implementation ("com.google.flogger:flogger:0.5.1")

    implementation ("com.google.firebase:firebase-messaging:24.1.0")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.generativeai)
    implementation(libs.androidx.preference.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)



}