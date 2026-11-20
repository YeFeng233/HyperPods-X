import javax.imageio.ImageIO
import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

/** Builds the two native-island icons from each original transparent pair, without resampling. */
@CacheableTask
abstract class GenerateIslandEarbuds : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceImages: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val output = outputDirectory.get().dir("drawable-nodpi").asFile
        check(output.isDirectory || output.mkdirs()) { "Cannot create the island artwork directory" }
        for (file in sourceImages.files.sortedBy { it.name }) {
            val product = file.name.removeSuffix("_pair.png")
            val source = checkNotNull(ImageIO.read(file)) { "Cannot read ${file.name}" }
            check(source.colorModel.hasAlpha()) { "${file.name} must have transparency" }
            val middle = source.width / 2
            check((0 until source.height).all { (source.getRGB(middle, it) ushr 24) == 0 }) {
                "${file.name} needs a transparent gap between the two earbuds"
            }
            for ((side, columns) in listOf("left" to (0 until middle), "right" to (middle until source.width))) {
                val visibleColumns = columns.filter { x ->
                    (0 until source.height).any { y -> (source.getRGB(x, y) ushr 24) != 0 }
                }
                check(visibleColumns.isNotEmpty()) { "The $side earbud is missing from ${file.name}" }
                val first = visibleColumns.first()
                val width = visibleColumns.last() - first + 1
                val earbud = source.getSubimage(first, 0, width, source.height)
                check(ImageIO.write(earbud, "png", output.resolve("${product}_$side.png"))) {
                    "Cannot write the $product $side island artwork"
                }
                logger.lifecycle("Island $product $side earbud: x=$first, width=$width, height=${source.height}")
            }
        }
    }
}

val generateIslandEarbuds = tasks.register<GenerateIslandEarbuds>("generateIslandEarbuds") {
    sourceImages.from(fileTree("src/main/res/drawable-nodpi") { include("*_pair.png") })
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
        versionCode = 59
        versionName = "0.5.1-dev"
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
    androidResources {
        noCompress += listOf("ttf", "otf")
        generateLocaleConfig = true
    }
    dependenciesInfo { includeInApk = false; includeInBundle = false }
}
dependencies {
    implementation(project(":core:protocol"))
    implementation(project(":core:wire"))
    implementation(project(":hook"))
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("io.github.libxposed:service:102.0.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.foundation:foundation:1.12.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("io.github.neoruaa.glasmio:glasmio-scaffold:0.1.3")
    implementation("io.github.neoruaa.glasmio:glasmio-preference:0.1.3")
    implementation("top.yukonga.miuix.kmp:miuix-ui:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-preference:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-icons:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-blur:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-navigation3-ui:0.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.2")
    testImplementation("junit:junit:4.13.2")
}
