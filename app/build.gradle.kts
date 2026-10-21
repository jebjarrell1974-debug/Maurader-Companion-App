plugins {
    alias(libs.plugins.android.application)
    // NO org.jetbrains.kotlin.android: AGP 9 has built-in Kotlin enabled by
    // default, and applying KGP conflicts with the new DSL.
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.kybercypher.marauder"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.kybercypher.marauder"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // AGP 9 defaults Java source/target to 11. jvmTarget is NOT set here:
    // with built-in Kotlin it defaults to compileOptions.targetCompatibility,
    // and the old kotlinOptions{} DSL is a hard compile error on KGP 2.4.x.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures { compose = true }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    implementation(libs.androidx.core)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.datastore)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.usb.serial)

    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
}
