plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
}

// Read OpenAI API key from local.properties and expose as BuildConfig field
val openAIApiKey: String? = project.findProperty("openai.api.key") as String?

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
            buildConfigField("String", "OPENAI_API_KEY", "\"${project.findProperty("openai.api.key") ?: ""}\"")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("String", "OPENAI_API_KEY", "\"${project.findProperty("openai.api.key") ?: ""}\"")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
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

    // Microsoft Authentication Library (MSAL)
    implementation("com.microsoft.identity.client:msal:5.1.0") {
        exclude(group = "com.microsoft.device.display", module = "display-mask")
        exclude(group = "org.slf4j", module = "slf4j-api")
    }

    // OkHttp for HTTP requests
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // JavaMail for SMTP email notifications
    implementation("com.sun.mail:android-mail:1.6.7")
    implementation("com.sun.mail:android-activation:1.6.7")
}