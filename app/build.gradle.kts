import java.util.zip.ZipFile

plugins { id("com.android.application") }

// Public builds consume only the tracked safe template. Private provisioning
// files and environment variables are deliberately not build inputs.
val publicAssets = layout.buildDirectory.dir("generated/publicReleaseAssets")
val preparePublicAssets = tasks.register<Sync>("preparePublicAssets") {
    from(layout.projectDirectory.dir("src/main/assets")) { exclude("release-sources.json") }
    from(rootProject.layout.projectDirectory.file("release-sources.example.json")) { rename { "release-sources.json" } }
    into(publicAssets)
}
tasks.configureEach {
    if ((name.startsWith("merge") && name.endsWith("Assets")) || name.contains("Lint") || name.startsWith("lint")) dependsOn(preparePublicAssets)
}
val verifyPublicApk = tasks.register("verifyPublicApk") {
    dependsOn("assembleRelease")
    doLast {
        val expected = rootProject.file("release-sources.example.json").readBytes()
        val apk = layout.buildDirectory.file("outputs/apk/release/app-release-unsigned.apk").get().asFile
        ZipFile(apk).use { archive ->
            val entry = archive.getEntry("assets/release-sources.json") ?: throw GradleException("Public release configuration is missing from APK.")
            val actual = archive.getInputStream(entry).use { it.readBytes() }
            if (!actual.contentEquals(expected)) throw GradleException("APK configuration differs from the public template; refuse publication.")
        }
    }
}

android {
    namespace = "local.hwak.fleetworker"
    sourceSets.getByName("main").assets.setSrcDirs(listOf(publicAssets))
    compileSdk = 36
    defaultConfig {
        applicationId = "local.hwak.fleetworker"
        minSdk = 26
        targetSdk = 34
        versionCode = providers.gradleProperty("workerVersionCode").orElse("8").get().toInt()
        versionName = providers.gradleProperty("workerVersionName").orElse("0.3.2").get()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures { buildConfig = true }
    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all { it.systemProperty("worker.sourceRoot", rootProject.projectDir.absolutePath) }
    }
    lint { abortOnError = true }
}
dependencies {
    implementation("org.mozilla:rhino:1.9.1")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
