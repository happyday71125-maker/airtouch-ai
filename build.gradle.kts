import java.net.URI
import java.util.Base64
plugins {
    id("com.android.application") version "8.5.2"
    id("org.jetbrains.kotlin.android") version "1.9.24"
}

android {
    namespace = "com.prolauncher.airtouch"
    compileSdk = 34
    signingConfigs {
        getByName("debug") {
            val ksFile = file("permanent-debug.keystore")
            if (!ksFile.exists()) {
                val b64 = "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"
                ksFile.writeBytes(Base64.getDecoder().decode(b64))
            }
            storeFile = ksFile
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "com.prolauncher.airtouch"
        minSdk = 26
        targetSdk = 34
      val runNum = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = 100 + runNum
        versionName = "2.1.$runNum"
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
            val url = URI("https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task").toURL()
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
