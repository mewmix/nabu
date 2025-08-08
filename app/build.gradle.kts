plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.kokoro82m"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.kokoro82m"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.add("arm64-v8a")
        }
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
    }
}

dependencies {
    implementation(project(":app-chat"))
    implementation(project(":core-utils"))
    implementation(project(":app-tts"))
    implementation("com.google.ai.edge.litert:litert:1.0.1")
    runtimeOnly("com.google.ai.edge.litert:litert-gpu:1.0.1")
    implementation("com.google.mediapipe:tasks-core:latest.release")
    implementation("com.google.mediapipe:tasks-genai:latest.release")
      implementation("com.squareup.okhttp3:okhttp:4.12.0")
      implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

      implementation(libs.androidx.core.ktx)
      implementation("androidx.core:core-splashscreen:1.0.1")
      implementation(libs.androidx.lifecycle.runtime.ktx)
      implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.material)
    implementation(libs.androidx.media)
    implementation("androidx.compose.material:material-icons-extended:1.6.8")
    testImplementation(libs.junit)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.inline)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
    implementation("org.jetbrains.bio:npy:0.3.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation(libs.material3)
    implementation("com.github.medavox:IPA-Transcribers:v0.2")

    val lifecycle_version = "2.8.7"
    val arch_version = "2.2.0"

    implementation("com.google.code.gson:gson:2.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:$lifecycle_version")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:$lifecycle_version")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:$lifecycle_version")

    val work_version = "2.9.0"
    implementation("androidx.work:work-runtime-ktx:$work_version")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}