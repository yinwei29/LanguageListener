plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}
android {
    namespace = "tw.language.listener"
    compileSdk = 35
    defaultConfig {
        applicationId = "tw.language.listener"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.4.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    // Every language must remain available offline, including future App Bundle installs.
    bundle { language { enableSplit = false } }
    // Optional stable debug identity for Google OAuth; no credentials are stored in source.
    System.getenv("LL_DEBUG_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }?.let { path ->
        signingConfigs.getByName("debug") {
            storeFile = file(path)
            storePassword = requireNotNull(System.getenv("LL_DEBUG_STORE_PASSWORD")) { "Missing LL_DEBUG_STORE_PASSWORD" }
            keyAlias = requireNotNull(System.getenv("LL_DEBUG_KEY_ALIAS")) { "Missing LL_DEBUG_KEY_ALIAS" }
            keyPassword = requireNotNull(System.getenv("LL_DEBUG_KEY_PASSWORD")) { "Missing LL_DEBUG_KEY_PASSWORD" }
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { task ->
            task.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
            // Forward explicitly supplied JVM network settings to Robolectric's SDK downloader.
            listOf("https.proxyHost", "https.proxyPort", "http.proxyHost", "http.proxyPort", "javax.net.ssl.trustStore").forEach { key ->
                System.getProperty(key)?.let { task.systemProperty(key, it) }
            }
        }
    }
    lint { abortOnError = true }
    sourceSets["test"].resources.srcDir("schemas")
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation(platform("androidx.compose:compose-bom:2025.02.00"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.datastore:datastore-preferences:1.1.3")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.android.gms:play-services-auth:21.3.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
}
