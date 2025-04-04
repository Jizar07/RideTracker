import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("com.google.gms.google-services")


}
buildscript {
    dependencies {
        classpath("com.google.gms:google-services:4.4.2")
    }
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
        val googleAIApiKey = localProperties.getProperty("GOOGLE_AI_API_KEY") ?: ""

        buildConfigField("String", "GOOGLE_AI_API_KEY", "\"${googleAIApiKey}\"")

        manifestPlaceholders["googleMapsKey"] = googleAIApiKey

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

    implementation ("org.osmdroid:osmdroid-android:6.1.10")  // osmdroid library for OpenStreetMap

    // Google Places SDK dependency providing access to POI search functionality.
//    implementation("com.google.android.libraries.places:places:4.1.0")
    implementation("com.google.ai.client.generativeai:generativeai:0.9.0")


//    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")

    // ----- Add Firebase Cloud-based ML Kit OCR dependency -----
//    implementation("com.google.mlkit:vision-text-recognition-cloud:16.0.0-beta3")
    // ----- ML Kit Text Recognition v2 (Unified API) -----
    implementation("com.google.mlkit:text-recognition:16.0.1")

    implementation("com.google.mlkit:camera:16.0.0-beta3")

    implementation ("com.google.flogger:flogger:0.5.1")

    implementation ("com.google.firebase:firebase-messaging:24.1.0")

    implementation("org.json:json:20210307")

    // Optionally, if you want Retrofit later:
     implementation("com.squareup.retrofit2:retrofit:2.9.0")
     implementation("com.squareup.retrofit2:converter-gson:2.9.0")

    implementation ("androidx.cardview:cardview:1.0.0")

    // ----- NEW: Import OkHttp dependencies -----
// Make sure these dependencies are added in your build.gradle file:
    implementation("com.squareup.okhttp3:okhttp:4.11.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.9.3")




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