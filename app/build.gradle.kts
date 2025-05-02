plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
}

// Read OpenAI API key directly from local.properties
val localPropertiesFile = rootProject.file("local.properties")
val properties = org.jetbrains.kotlin.konan.properties.Properties()
if (localPropertiesFile.exists()) {
    properties.load(localPropertiesFile.inputStream())
}
val openAIApiKey = properties.getProperty("OPENAI_API_KEY") ?: ""

android {
    namespace = "com.example.kenza"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.kenza"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    packaging {
        resources {
            excludes += listOf("META-INF/NOTICE.md", "META-INF/LICENSE.md")
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "OPENAI_API_KEY", "\"$openAIApiKey\"")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("String", "OPENAI_API_KEY", "\"$openAIApiKey\"")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
        // Correct the opt-in class name
        freeCompilerArgs = (freeCompilerArgs ?: listOf()) + "-Xopt-in=kotlin.ExperimentalStdlibApi"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")

    // Room
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    kapt("androidx.room:room-compiler:$roomVersion")

    // Microsoft Authentication Library (MSAL) - Updated to latest version
    implementation("com.microsoft.identity.client:msal:5.10.0") {
        exclude(group = "com.microsoft.device.display", module = "display-mask")
        exclude(group = "org.slf4j", module = "slf4j-api")
    }

    // OkHttp for HTTP requests
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // JavaMail for SMTP email notifications
    implementation("com.sun.mail:android-mail:1.6.7")
    implementation("com.sun.mail:android-activation:1.6.7")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.0") // Use the latest version
}