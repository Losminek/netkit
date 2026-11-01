import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}
/** Release version; versionCode = major*10000 + minor*100 + patch (docs/distribuce.md §2). */
val netkitVersion = "1.0.1"
val netkitVersionCode = netkitVersion.split('.').map(String::toInt).let { (major, minor, patch) ->
    require(minor in 0..99 && patch in 0..99) { "minor and patch must be 0..99" }
    major * 10000 + minor * 100 + patch
}

/**
 * Web base of the update check and project page (D17): `https://www.<domain>/netkit`, without a
 * trailing slash; `latest.json` lives at `<base>/latest.json`. The committed value is a placeholder:
 * debug/dev builds then compile with the update check off, a release build refuses to build
 * (see `checkReleaseConfig`). `-PnetkitAllowPlaceholderWeb=true` lets a local release build through
 * for an R8 test on the emulator; CI never passes it (release.yml).
 */
val netkitWebBase: String = providers.gradleProperty("netkitWebBase").get().trim()
val netkitSourceUrl: String = providers.gradleProperty("netkitSourceUrl").get().trim()
val netkitAllowPlaceholderWeb = providers.gradleProperty("netkitAllowPlaceholderWeb").map { it == "true" }.getOrElse(false)
val httpsUrl = Regex("^https://[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+(/[A-Za-z0-9._~-]+)*$")
require(httpsUrl.matches(netkitWebBase)) { "netkitWebBase must be https://<lowercase host>[/path] without a trailing slash" }
require(httpsUrl.matches(netkitSourceUrl)) { "netkitSourceUrl must be https://<lowercase host>[/path] without a trailing slash" }
/** Reserved names (RFC 2606/6761) and template markers are placeholders, never a real web. */
val netkitWebConfigured = netkitWebBase.removePrefix("https://").substringBefore('/').let { host ->
    host.split('.').last() !in setOf("invalid", "test", "example", "localhost", "local") &&
        host.removePrefix("www.") !in setOf("example.com", "example.net", "example.org")
}

android {
    namespace = "cz.netkit.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "cz.netkit.app"
        val testSuite = providers.gradleProperty("netkitTestSuite").getOrElse("m1")
        require(testSuite in setOf("m1", "wol")) { "netkitTestSuite must be m1 or wol" }
        testInstrumentationRunner = if (testSuite == "wol")
            "cz.netkit.app.test.NetKitInstrumentation"
        else "cz.netkit.app.test.M1Instrumentation"
        minSdk = 29
        targetSdk = 35
        versionCode = netkitVersionCode
        versionName = netkitVersion
        buildConfigField("String", "UPDATE_BASE_URL", "\"$netkitWebBase\"")
        buildConfigField("boolean", "UPDATE_CONFIGURED", netkitWebConfigured.toString())
        buildConfigField("String", "SOURCE_URL", "\"$netkitSourceUrl\"")
    }
    // Release signing (D11): only from a local, git-ignored keystore.properties in the repo root.
    // Without it assembleRelease still succeeds and produces an unsigned APK (CI, R8 tests).
    val keystoreFile = rootProject.file("keystore.properties")
    val releaseSigning = if (keystoreFile.isFile) {
        val props = Properties().apply { keystoreFile.inputStream().use { load(it) } }
        // Keys of keystore.properties, in this order; the values never leave that local file.
        val (file, storeSecret, alias, keySecret) = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
            .map { key -> props.getProperty(key) ?: error("keystore.properties: missing $key") }
        signingConfigs.create("release") {
            storeFile = rootProject.file(file)
            storePassword = storeSecret
            keyAlias = alias
            keyPassword = keySecret
        }
    } else null
    buildTypes {
        // Instrumentation and unit tests keep the plain debug variant (cz.netkit.app).
        debug { versionNameSuffix = "-debug" }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = releaseSigning
        }
        // Phone test builds (D21): installed next to the release app, debug-signed.
        create("dev") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            matchingFallbacks += listOf("debug")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    // The dependency block is encrypted for Google only; an APK from GitHub has no use for it.
    dependenciesInfo { includeInApk = false; includeInBundle = false }
    packaging.resources {
        // OSGi bundle descriptors are not consumed by Android; keep library licenses.
        excludes += "META-INF/versions/*/OSGI-INF/MANIFEST.MF"
        merges += setOf("META-INF/LICENSE*", "META-INF/NOTICE*")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
kotlin { jvmToolchain(21) }

/** D17: a release must carry the real web base; a placeholder would ship a dead update check. */
val checkReleaseConfig by tasks.registering {
    val configured = netkitWebConfigured
    val allowed = netkitAllowPlaceholderWeb
    val base = netkitWebBase
    doLast {
        if (!configured && !allowed) throw GradleException(
            "netkitWebBase is a placeholder ($base); set the real web in gradle.properties (D17). " +
                "Local R8 test only: -PnetkitAllowPlaceholderWeb=true")
    }
}
tasks.configureEach { if (name == "preReleaseBuild") dependsOn(checkReleaseConfig) }
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    useTestNG()
    // Reproducible native diagnostics; production classification must not depend on these texts.
    environment("LC_ALL", "C")
}

dependencies {
    implementation(libs.jsch)
    implementation(libs.bcprov)
    // Speed test: HTTPS (LibreSpeed) and WebSocket (NDT7) bound to the Wi-Fi Network.
    implementation(libs.okhttp)
    testImplementation(kotlin("test-testng"))
    testImplementation(libs.testng)
    testImplementation(libs.coroutines.test)
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    implementation(libs.datastore.core)
    // Also compile-visible so lint can resolve the removed manifest receiver.
    implementation(libs.profileinstaller)
    implementation(project(":shared"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel)
}
