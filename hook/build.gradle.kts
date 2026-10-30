plugins { id("com.android.library") }
android {
    namespace = "com.hyper.podsrx.hook"
    compileSdk = 37
    defaultConfig { minSdk = 36 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation(project(":core:wire"))
    // The module runs from the app's APK, whose dex already carries these and the Kotlin stdlib.
    implementation(project(":core:protocol"))
}
