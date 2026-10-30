plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
}

// Samsung Health Data SDK is distributed by Samsung as an AAR (not via Maven).
// When the AAR is present in app/libs the real adapter is compiled in,
// otherwise an adapter that reports "SDK not bundled" is used.
val samsungSdkAar = fileTree("libs") { include("samsung-health-data-api*.aar") }
val hasSamsungSdk = !samsungSdkAar.isEmpty

// Release credentials are supplied locally or by CI, never committed to the repository.
val releaseSigning = listOf("NUTRIX_KEYSTORE_PATH", "NUTRIX_KEYSTORE_PASSWORD", "NUTRIX_KEY_ALIAS", "NUTRIX_KEY_PASSWORD")
    .associateWith { providers.environmentVariable(it).orNull }
require(releaseSigning.values.all { it.isNullOrBlank() } || releaseSigning.values.all { !it.isNullOrBlank() }) {
    "Set all four NUTRIX_KEYSTORE_PATH, NUTRIX_KEYSTORE_PASSWORD, NUTRIX_KEY_ALIAS and NUTRIX_KEY_PASSWORD variables"
}
val hasReleaseSigning = releaseSigning.values.all { !it.isNullOrBlank() }

android {
    namespace = "com.nutrix.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nutrix.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 12
        versionName = "1.10.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "SAMSUNG_SDK_BUNDLED", hasSamsungSdk.toString())
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        getByName("debug") {
            // Project-local debug key so every build of the debug APK has the same signature
            // (updates install over each other; SHA-256 stays stable for Samsung developer mode).
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (hasReleaseSigning) {
            create("privateRelease") {
                storeFile = file(releaseSigning.getValue("NUTRIX_KEYSTORE_PATH")!!)
                storePassword = releaseSigning.getValue("NUTRIX_KEYSTORE_PASSWORD")
                keyAlias = releaseSigning.getValue("NUTRIX_KEY_ALIAS")
                keyPassword = releaseSigning.getValue("NUTRIX_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("privateRelease") else null
        }
        create("personal") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            versionNameSuffix = "-personal"
        }
    }

    sourceSets {
        getByName("main") {
            java.srcDir(if (hasSamsungSdk) "src/samsungSdk/kotlin" else "src/samsungStub/kotlin")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
            )
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            all { test ->
                test.maxHeapSize = "3g"
                // Optional mirror for Robolectric's Android runtime jars (set in ~/.gradle/gradle.properties).
                providers.gradleProperty("robolectricRepoUrl").orNull?.let {
                    test.systemProperty("robolectric.dependency.repo.url", it)
                }
            }
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.coroutines.FlowPreview",
            "-Xannotation-default-target=param-property",
        )
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.okhttp)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.animation)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.datastore.preferences)
    implementation(libs.work.runtime.ktx)
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.health.connect)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    implementation(libs.biometric)
    implementation(libs.exifinterface)
    implementation(libs.coil.compose)
    implementation(libs.vico.compose.m3)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.zip4j)

    if (hasSamsungSdk) {
        implementation(samsungSdkAar)
        implementation(libs.gson) // required at runtime by the Samsung Health Data SDK
    }

    testImplementation(libs.mockwebserver)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.room.testing)
    testImplementation(libs.work.testing)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.compiler)
    debugImplementation(libs.compose.ui.test.manifest)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
}
