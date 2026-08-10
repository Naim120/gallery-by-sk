plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
}

android {
    namespace = "com.sk.gallery"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sk.gallery"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        val secretsFile = rootProject.file("secrets.properties")
        val secrets = java.util.Properties()
        if (secretsFile.exists()) {
            secrets.load(java.io.FileInputStream(secretsFile))
        }
        val clientId = secrets.getProperty("OAUTH_CLIENT_ID", "YOUR_CLIENT_ID_HERE")
        
        manifestPlaceholders["oauthClientId"] = clientId
        buildConfigField("String", "OAUTH_CLIENT_ID", "\"$clientId\"")
        buildConfigField("String", "DRIVE_APPDATA_SCOPE", "\"https://www.googleapis.com/auth/drive.appdata\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        viewBinding = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE"
            excludes += "META-INF/LICENSE.txt"
            excludes += "META-INF/license.txt"
            excludes += "META-INF/NOTICE"
            excludes += "META-INF/NOTICE.txt"
            excludes += "META-INF/notice.txt"
            excludes += "META-INF/ASL2.0"
            excludes += "META-INF/*.kotlin_module"
        }
    }
}

dependencies {
    // AndroidX Core & UI
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.6.2")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")

    // Google Play Services Auth
    implementation("com.google.android.gms:play-services-auth:21.0.0")

    // Google Drive API Client Java/Android
    implementation("com.google.api-client:google-api-client-android:2.2.0") {
        exclude(group = "org.apache.httpcomponents")
    }
    implementation("com.google.apis:google-api-services-drive:v3-rev20231128-2.0.0") {
        exclude(group = "org.apache.httpcomponents")
    }
    implementation("com.google.http-client:google-http-client-gson:1.43.3")

    // WorkManager KTX
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Glide (Image Loading)
    implementation("com.github.bumptech.glide:glide:4.16.0")
    kapt("com.github.bumptech.glide:compiler:4.16.0")

    // Gson (JSON processing)
    implementation("com.google.code.gson:gson:2.10.1")

    // Room Database
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    // Biometrics
    implementation("androidx.biometric:biometric:1.1.0")

    // Android-Image-Cropper (CanHub)
    implementation("com.vanniktech:android-image-cropper:4.5.0")

    // PhotoEditor (Burhanrashid52)
    implementation("com.burhanrashid52:photoeditor:3.1.0")
    
    // ColorPicker
    implementation("com.github.skydoves:colorpickerview:2.3.0")

    // Media3 (Video Editing)
    val media3Version = "1.2.0"
    implementation("androidx.media3:media3-transformer:$media3Version")
    implementation("androidx.media3:media3-effect:$media3Version")
    implementation("androidx.media3:media3-common:$media3Version")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
