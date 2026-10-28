plugins { id("com.android.library") }
android {
    namespace = "com.hyper.podsrx.wire"
    compileSdk = 37
    defaultConfig { minSdk = 37 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
dependencies {
    api(project(":core:protocol"))
    testImplementation("junit:junit:4.13.2")
    // Bundle needs a real framework; the tests pin Robolectric's newest runtime in place of API 37.
    testImplementation("org.robolectric:robolectric:4.16")
}
