plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val licenseAssetsDir = layout.buildDirectory.dir("generated/license-assets")
val collectLicenseAssets by tasks.registering(Sync::class) {
    into(licenseAssetsDir)
    from(rootProject.file("LICENSE"))
    from(rootProject.file("THIRD_PARTY.md"))
    from(rootProject.file("third-party")) { into("third-party") }
}
android {
    namespace = "io.github.xiaomibutton.remapper"
    compileSdk = 37
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "io.github.xiaomibutton.remapper"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild {
            cmake { arguments += "-DANDROID_STL=c++_static" }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    sourceSets["main"].assets.directories.add(licenseAssetsDir.get().asFile.path)
    buildFeatures {
        aidl = true
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Shizuku's app_process UserService loads our JNI library from nativeLibraryDir.
    packaging { jniLibs.useLegacyPackaging = true }
}
tasks.named("preBuild") { dependsOn(collectLicenseAssets) }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
