import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.gameconsole.launcher3ds"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.gameconsole.launcher3ds"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val keystorePropertiesFile = rootProject.file("keystore.properties")
    val defaultLocalKeystore = rootProject.file("app/signing/my-release-key.jks")

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                val properties = Properties()
                keystorePropertiesFile.inputStream().use { properties.load(it) }
                storeFile = rootProject.file(properties.getProperty("storeFile", "my-release-key.jks"))
                storePassword = properties.getProperty("storePassword", "")
                keyAlias = properties.getProperty("keyAlias", "")
                keyPassword = properties.getProperty("keyPassword", "")
            } else if (defaultLocalKeystore.exists()) {
                storeFile = defaultLocalKeystore
                storePassword = "MyAppStorePass_123!"
                keyAlias = "myapp"
                keyPassword = "MyAppStorePass_123!"
            } else {
                // Open-source fallback: use standard Android debug key for signing release builds
                initWith(getByName("debug"))
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    aaptOptions {
        noCompress += listOf("wav", "mp3", "ogg")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
    jvmToolchain(17)
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.core.ktx)
    implementation("com.google.code.gson:gson:2.10.1")
}

// 自动导出签名后的独立 APK (如本地目标目录存在则复制)
tasks.matching { it.name == "assembleRelease" }.configureEach {
    doLast {
        val releaseApk = file("build/outputs/apk/release/app-release.apk")
        if (releaseApk.exists()) {
            val targetDir = File("D:/apollo-apk")
            if (targetDir.exists()) {
                val targetApk = File(targetDir, "apollo-3ds.apk")
                releaseApk.copyTo(targetApk, overwrite = true)
                val legacyTargetApk = File(targetDir, "Launcher3DS.apk")
                releaseApk.copyTo(legacyTargetApk, overwrite = true)
                println("[Auto-Export] Successfully copied Apollo 3DS APK to ${targetApk.absolutePath}")
            }

            val releasesDir = rootProject.file("releases")
            if (releasesDir.exists()) {
                val repoApk = File(releasesDir, "apollo-3ds.apk")
                releaseApk.copyTo(repoApk, overwrite = true)
                val legacyRepoApk = File(releasesDir, "Launcher3DS.apk")
                releaseApk.copyTo(legacyRepoApk, overwrite = true)
                println("[Auto-Export] Also copied Apollo 3DS APK to ${repoApk.absolutePath}")
            }
        }
    }
}
