plugins { id("com.android.application") version "9.2.1" }

android {
    namespace = "com.beautifulquran.peerjsprobe"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.beautifulquran.peerjsprobe"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    buildTypes { release { signingConfig = signingConfigs.getByName("debug") } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
dependencies {
    implementation("io.github.webrtc-sdk:android:150.7871.01")
    implementation("org.java-websocket:Java-WebSocket:1.6.0")
}
