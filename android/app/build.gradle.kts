plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.truckcontroller.pro"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.truckcontroller.pro"
        minSdk = 28 // Android 9.0 introduces standard BluetoothHidDevice API
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // QR / barcode scanner: CameraX preview + zxing-cpp decoder (offline, no Play Services)
    val cameraX = "1.3.4"
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")
    implementation("io.github.zxing-cpp:android:2.2.0")
    // QR generator: encoder only, drawn with custom designs
    implementation("io.nayuki:qrcodegen:1.8.0")
    // Trip routes on OpenStreetMap (no API key; tiles cached in the app's cache)
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    // LSPosed module: hooks run inside System UI, the API is provided by the framework at runtime
    compileOnly("de.robv.android.xposed:api:82")
}
