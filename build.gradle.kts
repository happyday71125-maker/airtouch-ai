plugins {
    id("com.android.application") version "8.5.2"
    id("org.jetbrains.kotlin.android") version "1.9.24"
}

android {
    namespace = "com.prolauncher.airtouch"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.prolauncher.airtouch"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0-lowram"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    androidResources {
        noCompress += listOf("task", "tflite", "litertlm")
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    doFirst {
        val assetDir = file("src/main/assets")
        assetDir.mkdirs()
        val modelFile = file("src/main/assets/hand_landmarker.task")
        if (!modelFile.exists() || modelFile.length() < 100000L) {
            val url = java.net.URI("https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task").toURL()
            url.openStream().use { input ->
                modelFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")

    val cameraVersion = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraVersion")
    implementation("androidx.camera:camera-camera2:$cameraVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraVersion")

    implementation("com.google.mediapipe:tasks-vision:0.10.14")
}
