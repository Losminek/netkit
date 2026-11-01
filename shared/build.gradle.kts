import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
}
kotlin {
    androidTarget()
    jvm()
    jvmToolchain(21)
    // Keep the existing Linux/Android build free of Apple targets by default.
    if (System.getProperty("os.name").startsWith("Mac", ignoreCase = true) ||
        providers.gradleProperty("netkit.ios").orNull == "true"
    ) {
        val xcframework = XCFramework("NetKitShared")
        listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
            target.binaries.framework {
                baseName = "NetKitShared"
                isStatic = true
                binaryOption("bundleId", "cz.netkit.shared")
                xcframework.add(this)
            }
        }
    }
    sourceSets {
        commonMain.dependencies {
            implementation(libs.coroutines.core)
            implementation(libs.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.coroutines.test)
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation(kotlin("test-testng"))
            implementation(libs.testng)
        }
        androidUnitTest.dependencies {
            implementation(kotlin("test-testng"))
            implementation(libs.testng)
        }
    }
}
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    useTestNG()
    // Reproducible native diagnostics; production classification must not depend on these texts.
    environment("LC_ALL", "C")
}
android {
    namespace = "cz.netkit.shared"
    compileSdk = 35
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
