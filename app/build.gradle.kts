plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.hyper.podsrx"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    defaultConfig {
        applicationId = "com.hyper.podsrx"
        minSdk = 37
        targetSdk = 37
        versionCode = 12
        versionName = "0.4.6-dev"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures { compose = true; buildConfig = true }
    providers.environmentVariable("HYPERPODS_TEST_KEYSTORE").orNull?.let { path ->
        signingConfigs.getByName("debug") {
            storeFile = file(path)
        }
    }
    buildTypes {
        release { isMinifyEnabled = true; isShrinkResources = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") }
        create("deviceTest") {
            initWith(getByName("release"))
            signingConfig=signingConfigs.getByName("debug")
            matchingFallbacks+=listOf("release")
            versionNameSuffix="-device-test"
        }
    }
    packaging { resources.merges += setOf("META-INF/LICENSE*", "META-INF/NOTICE*") }
    dependenciesInfo { includeInApk = false; includeInBundle = false }
}
dependencies {
    implementation(project(":core:protocol"))
    implementation(project(":hook"))
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("io.github.libxposed:service:102.0.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("org.jetbrains.compose.foundation:foundation:1.12.0")
    implementation("org.jetbrains.compose.ui:ui:1.12.0")
    implementation("top.yukonga.miuix.kmp:miuix-ui:0.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.2")
    testImplementation("junit:junit:4.13.2")
}
