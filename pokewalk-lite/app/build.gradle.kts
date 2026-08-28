plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.pokewalklite"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.pokewalklite"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.12.1")
    implementation("androidx.health.connect:connect-client:1.2.0-alpha06")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
