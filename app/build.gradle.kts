plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseBuildNumber = providers.gradleProperty("releaseBuildNumber").map { it.toInt() }.orElse(0)

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

    defaultConfig {
        applicationId = "io.github.xiaomibutton.remapper"
        minSdk = 30
        targetSdk = 37
        versionCode = 2 + releaseBuildNumber.get()
        versionName = "0.2.${releaseBuildNumber.get()}"
    }
    sourceSets["main"].assets.directories.add(licenseAssetsDir.get().asFile.path)
    buildFeatures {
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
tasks.named("preBuild") { dependsOn(collectLicenseAssets) }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
