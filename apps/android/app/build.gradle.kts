plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val releaseStoreFile = System.getenv("NOOK_RELEASE_KEYSTORE_PATH")
val releaseStorePassword = System.getenv("NOOK_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = System.getenv("NOOK_RELEASE_KEY_ALIAS")
val releaseKeyPassword = System.getenv("NOOK_RELEASE_KEY_PASSWORD")
val releaseSigningValues = listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
val releaseSigningConfigured = releaseSigningValues.all { !it.isNullOrBlank() }
val releaseSigningRequested = gradle.startParameter.taskNames.any {
    it.substringAfterLast(':').lowercase() in setOf("assemblerelease", "packagerelease", "bundlerelease", "installrelease")
}
if (releaseSigningValues.any { !it.isNullOrBlank() } && !releaseSigningConfigured) {
    throw GradleException("Release signing requires NOOK_RELEASE_KEYSTORE_PATH, NOOK_RELEASE_STORE_PASSWORD, NOOK_RELEASE_KEY_ALIAS, and NOOK_RELEASE_KEY_PASSWORD.")
}
if (releaseSigningRequested && !releaseSigningConfigured) {
    throw GradleException("assembleRelease requires the permanent Nook release signing environment variables.")
}
if (releaseSigningConfigured && !file(releaseStoreFile!!).isFile) {
    throw GradleException("The configured Nook release keystore file is missing.")
}

val releaseCertificateSha256 = rootProject.file("release-certificate.sha256").readText().trim().uppercase()
if (!releaseCertificateSha256.matches(Regex("^[A-F0-9]{64}$"))) {
    throw GradleException("apps/android/release-certificate.sha256 must contain a 64-character SHA-256 fingerprint.")
}

android {
    namespace = "app.nook"
    compileSdk = 36
    defaultConfig {
        applicationId = "app.nook"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "NOOK_RELEASE_CERT_SHA256", "\"$releaseCertificateSha256\"")
    }
    buildFeatures { compose = true; buildConfig = true }
    signingConfigs {
        create("nookRelease") {
            if (releaseSigningConfigured) {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                storeType = "PKCS12"
            }
        }
    }
    buildTypes {
        getByName("release") {
            if (releaseSigningConfigured) signingConfig = signingConfigs.getByName("nookRelease")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    testOptions { unitTests.isIncludeAndroidResources = true }
    sourceSets.getByName("test").resources.srcDir("../../../packages/schemas/fixtures")
    sourceSets.getByName("test").resources.srcDir("schemas")
}
kotlin {
    jvmToolchain(21)
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
// Both variants export the same conceptual schema; avoid competing writes on first generation.
tasks.matching { it.name == "kspReleaseKotlin" }.configureEach { mustRunAfter("kspDebugKotlin") }
dependencies {
    implementation(platform("com.google.firebase:firebase-bom:34.19.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-storage")
    implementation("androidx.credentials:credentials:1.6.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.6.0")
    // Firebase's documented adapter version supports the project's Kotlin 2.2 compiler.
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
    implementation("androidx.work:work-runtime-ktx:2.10.5")
    implementation(platform("androidx.compose:compose-bom:2025.09.01"))
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-process:2.9.4")
    implementation("androidx.glance:glance-appwidget:1.2.0")
    implementation("com.caverock:androidsvg-aar:1.4")
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:ext-tasklist:4.6.2")
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.09.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.work:work-testing:2.10.5")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
