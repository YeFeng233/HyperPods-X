import javax.imageio.ImageIO
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

/** Builds the two native-island icons from the original transparent pair, without resampling. */
@CacheableTask
abstract class GenerateIslandEarbuds : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceImage: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val source = checkNotNull(ImageIO.read(sourceImage.get().asFile)) { "Cannot read the AirPods pair artwork" }
        check(source.colorModel.hasAlpha()) { "The AirPods pair artwork must have transparency" }
        val middle = source.width / 2
        check((0 until source.height).all { (source.getRGB(middle, it) ushr 24) == 0 }) {
            "The AirPods pair artwork needs a transparent gap between the two earbuds"
        }
        val output = outputDirectory.get().dir("drawable-nodpi").asFile
        check(output.isDirectory || output.mkdirs()) { "Cannot create the island artwork directory" }
        for ((side, columns) in listOf("left" to (0 until middle), "right" to (middle until source.width))) {
            val visibleColumns = columns.filter { x ->
                (0 until source.height).any { y -> (source.getRGB(x, y) ushr 24) != 0 }
            }
            check(visibleColumns.isNotEmpty()) { "The $side earbud is missing from the pair artwork" }
            val first = visibleColumns.first()
            val width = visibleColumns.last() - first + 1
            val earbud = source.getSubimage(first, 0, width, source.height)
            check(ImageIO.write(earbud, "png", output.resolve("airpods5_$side.png"))) {
                "Cannot write the $side island artwork"
            }
            logger.lifecycle("Island $side earbud: x=$first, width=$width, height=${source.height}")
        }
    }
}

val generateIslandEarbuds = tasks.register<GenerateIslandEarbuds>("generateIslandEarbuds") {
    sourceImage.set(layout.projectDirectory.file("src/main/res/drawable-nodpi/airpods5_pair.png"))
    outputDirectory.set(layout.buildDirectory.dir("generated/res/island-earbuds"))
}

androidComponents.onVariants { variant ->
    variant.sources.res?.addGeneratedSourceDirectory(generateIslandEarbuds, GenerateIslandEarbuds::outputDirectory)
}

android {
    namespace = "com.hyper.podsrx"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    defaultConfig {
        applicationId = "com.hyper.podsrx"
        minSdk = 37
        targetSdk = 37
        versionCode = 14
        versionName = "0.4.8-dev"
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
