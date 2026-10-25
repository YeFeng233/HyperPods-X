plugins { id("com.android.library") }
android {
    namespace = "com.hyper.podsrx.hook"
    compileSdk = 37
    defaultConfig { minSdk = 37 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
dependencies { compileOnly("io.github.libxposed:api:102.0.0") }
