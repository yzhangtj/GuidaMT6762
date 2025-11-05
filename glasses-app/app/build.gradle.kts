plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

import java.util.Properties

// Load local properties for secrets without committing them
val localProps = Properties().apply {
    val localFile = rootProject.file("local.properties")
    if (localFile.exists()) {
        localFile.inputStream().use { this.load(it) }
    }
}

val qwenKey: String = localProps.getProperty("QWEN_API_KEY", "")
val openaiKey: String = localProps.getProperty("OPENAI_API_KEY", "")
val moondreamKey: String = localProps.getProperty("MOONDREAM_API_KEY", "")
val proxyHost: String = localProps.getProperty("PROXY_HOST", "")
val proxyPort: String = localProps.getProperty("PROXY_PORT", "")

android {
    namespace = "com.guidaco.guidaglassesapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.guidaco.guidaglassesapp"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // Expose keys via BuildConfig (values come from local.properties)
        buildConfigField("String", "QWEN_API_KEY", '"' + qwenKey.replace("\"", "\\\"") + '"')
        buildConfigField("String", "OPENAI_API_KEY", '"' + openaiKey.replace("\"", "\\\"") + '"')
        buildConfigField("String", "MOONDREAM_API_KEY", '"' + moondreamKey.replace("\"", "\\\"") + '"')
        buildConfigField("String", "PROXY_HOST", '"' + proxyHost.replace("\"", "\\\"") + '"')
        buildConfigField("String", "PROXY_PORT", '"' + proxyPort.replace("\"", "\\\"") + '"')
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
        compose = true
        buildConfig = true
    }
    lint {
        disable += "FlowOperatorInvokedInComposition"
        disable += "StateFlowValueCalledInComposition"
        disable += "CoroutineCreationDuringComposition"
        abortOnError = false
    }
}

// Removed duplicate applicationVariants block: BuildConfig fields are set in defaultConfig

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    
    // Camera and CameraX dependencies
    implementation("androidx.camera:camera-core:1.3.1")
    implementation("androidx.camera:camera-camera2:1.3.1")
    implementation("androidx.camera:camera-lifecycle:1.3.1")
    
    // Network requests
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    
    // Permissions handling
    implementation("com.google.accompanist:accompanist-permissions:0.32.0")
    
    // ViewModel and Navigation
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.navigation:navigation-compose:2.7.6")
    
    // Preferences for settings
    implementation("androidx.datastore:datastore-preferences:1.0.0")
    
    // Vosk offline speech recognition
    implementation("net.java.dev.jna:jna:5.12.1@aar")
    implementation("com.alphacephei:vosk-android:0.3.47")
    
    // Added from the code block
    implementation("androidx.lifecycle:lifecycle-viewmodel:2.7.0")
    
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}