plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.kapt")
    id("com.google.dagger.hilt.android")
}

val signingPath = providers.environmentVariable("CLASSHER_SIGNING_STORE_FILE").orNull
val signingPassword = providers.environmentVariable("CLASSHER_SIGNING_PASSWORD").orNull
val stableSigningRequired = providers.environmentVariable("CLASSHER_REQUIRE_STABLE_SIGNING").orNull == "true"
if (stableSigningRequired || signingPath != null || signingPassword != null) {
    require(!signingPath.isNullOrBlank() && !signingPassword.isNullOrBlank()) { "Stable trial signing is not configured" }
    require(file(signingPath).isFile) { "Trial signing file is unavailable" }
}

android {
    namespace = "com.classher.timetable"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.classher.timetable"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.1-trial"
    }
    buildFeatures { compose = true }
    sourceSets.getByName("test").resources.srcDir("schemas")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    signingConfigs {
        if (signingPath != null && signingPassword != null) create("trial") {
            storeFile = file(signingPath)
            storePassword = signingPassword
            keyAlias = "classher-trial"
            keyPassword = signingPassword
            storeType = "JKS"
        }
    }
    buildTypes {
        getByName("debug") {
            if (signingPath != null && signingPassword != null) signingConfig = signingConfigs.getByName("trial")
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = false
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED", "--add-opens=java.base/java.util=ALL-UNNAMED",
                "--add-opens=java.base/java.io=ALL-UNNAMED", "--add-opens=java.base/java.net=ALL-UNNAMED",
                "--add-opens=java.base/java.security=ALL-UNNAMED", "--add-opens=java.base/java.text=ALL-UNNAMED",
                "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED", "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
                "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED")
        }
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

kapt {
    correctErrorTypes = true
    arguments { arg("room.schemaLocation", "$projectDir/schemas") }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.google.dagger:hilt-android:2.57.2")
    kapt("com.google.dagger:hilt-compiler:2.57.2")
    implementation("org.jsoup:jsoup:1.21.2")
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    kapt("androidx.room:room-compiler:2.8.5")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test:core:1.7.0")
}
