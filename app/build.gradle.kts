plugins {
    alias(libs.plugins.android.application)
}

val occupancyBaseUrl = providers.gradleProperty("gymOccupancyBaseUrl")
    .orElse("http://10.0.2.2:8080/").get()
val occupancyDeviceId = providers.gradleProperty("gymOccupancyDeviceId")
    .orElse("counter-01").get()
val occupancyRoomId = providers.gradleProperty("gymOccupancyRoomId")
    .orElse("room-01").get()

android {
    namespace = "com.example.posebenchmark"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.posebenchmark"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "OCCUPANCY_BASE_URL", "\"$occupancyBaseUrl\"")
        buildConfigField("String", "OCCUPANCY_DEVICE_ID", "\"$occupancyDeviceId\"")
        buildConfigField("String", "OCCUPANCY_ROOM_ID", "\"$occupancyRoomId\"")
    }

    buildFeatures { buildConfig = true }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    val cameraXVersion = "1.6.2"

    implementation("androidx.activity:activity-ktx:1.11.0")

    implementation("androidx.camera:camera-core:$cameraXVersion")
    implementation("androidx.camera:camera-camera2:$cameraXVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraXVersion")
    implementation("androidx.camera:camera-view:$cameraXVersion")
    implementation("com.google.android.material:material:1.12.0")

    //MEDIAPIPE
    implementation("com.google.mediapipe:tasks-vision:1.0.0")
}
