plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.leastview.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.leastview.app"
        minSdk = 26
        targetSdk = 35
        // versionCode only has to keep rising so an installed build can be updated in place; the name users see is versionName
        versionCode = 4
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            // Release builds are much smoother than debug (Compose is slow in debug). Signed with the
            // debug key so the APK installs directly; swap in a real keystore before publishing.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        // PdfBox logs through android.util.Log, which has no implementation in plain JVM tests
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    // PDF text extraction (search), merge and split. Android's PdfRenderer can only draw pages.
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    // read the EXIF rotation of photos before resizing them
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    testImplementation("junit:junit:4.13.2")
}
