import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.mewmix.nabu"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mewmix.nabu"
        minSdk = 29
        targetSdk = 35
        versionCode = 18
        versionName = "0.5.12"

        val gitCommitHashProvider = providers.exec {
            commandLine("git", "rev-parse", "--short=7", "HEAD")
        }.standardOutput.asText.map { it.trim() }.orElse("unknown")
        buildConfigField("String", "GIT_COMMIT_HASH", "\"${gitCommitHashProvider.get()}\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            val props = Properties()
            val propFile = file("../local.properties")
            if (propFile.exists()) {
                props.load(FileInputStream(propFile))
            }
            
            val storeFilePath = System.getenv("RELEASE_STORE_FILE") ?: props.getProperty("RELEASE_STORE_FILE")
            if (storeFilePath != null) {
                storeFile = file(storeFilePath)
                storePassword = System.getenv("RELEASE_STORE_PASSWORD") ?: props.getProperty("RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS") ?: props.getProperty("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD") ?: props.getProperty("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            val releaseSigning = signingConfigs.getByName("release")
            signingConfig = if (releaseSigning.storeFile != null) releaseSigning else signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
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
}

tasks.matching { it.name.startsWith("generate") && it.name.endsWith("BuildConfig") }.configureEach {
    val gitCommitHashProvider = providers.exec {
        commandLine("git", "rev-parse", "--short=7", "HEAD")
    }.standardOutput.asText.map { it.trim() }.orElse("unknown")
    inputs.property("gitCommitHash", gitCommitHashProvider)
}

dependencies {
    implementation(project(":app-chat"))
    implementation(project(":core-utils"))
    implementation("com.google.ai.edge.litert:litert:1.0.1")
    runtimeOnly("com.google.ai.edge.litert:litert-gpu:1.0.1")
    implementation("com.google.mediapipe:tasks-core:latest.release")
    implementation("com.google.mediapipe:tasks-genai:latest.release")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")
    implementation("io.ktor:ktor-server-core:2.3.12")
    implementation("io.ktor:ktor-server-cio:2.3.12")

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
    testImplementation(libs.androidx.junit)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.inline)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("org.robolectric:robolectric:4.11.1")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
    implementation("org.jetbrains.bio:npy:0.3.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.github.medavox:IPA-Transcribers:v0.2")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("org.jsoup:jsoup:1.17.2")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

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
