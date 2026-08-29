plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.pokewalklite"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.pokewalklite"
        minSdk = 28
        targetSdk = 36
        versionCode = 13
        versionName = "0.4.9"
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.12.1")
    implementation("androidx.health.connect:connect-client:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
