import java.io.File
import java.util.Properties
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

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
        versionCode = 11
        versionName = "0.4.5-dev"
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
    androidResources { noCompress += listOf("ttf", "otf") }
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
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.foundation:foundation:1.12.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("io.github.neoruaa.glasmio:glasmio-scaffold:0.1.0")
    implementation("io.github.neoruaa.glasmio:glasmio-preference:0.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.2")
    testImplementation("junit:junit:4.13.2")
}

/** Host-owned fonts are copied only into generated APK assets. */
abstract class GenerateHyperPodsFontAssets : DefaultTask() {
    @get:InputFile @get:Optional @get:PathSensitive(PathSensitivity.NONE)
    abstract val sourceFont: RegularFileProperty
    @get:InputFile @get:Optional @get:PathSensitive(PathSensitivity.NONE)
    abstract val sourceItalicFont: RegularFileProperty
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction fun generate() {
        require(sourceFont.isPresent) {
            "SF Pro is required. Set glasmio.symbolFont in local.properties or pass -Pglasmio.symbolFont=/path/SF-Pro.ttf."
        }
        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        output.mkdirs()
        fun copy(source: RegularFileProperty, name: String) {
            source.orNull?.asFile?.let { font ->
                val signature = font.inputStream().use { it.readNBytes(4).toList() }
                require(signature in listOf(
                    listOf<Byte>(0, 1, 0, 0), "OTTO".toByteArray().toList(),
                    "ttcf".toByteArray().toList(), "true".toByteArray().toList()
                )) { "$name must be an OpenType or TrueType font." }
                font.copyTo(output.resolve(name), overwrite = true)
            }
        }
        copy(sourceFont, "sf_pro.ttf")
        copy(sourceItalicFont, "sf_pro_italic.ttf")
    }
}

val fontProperties = providers.fileContents(rootProject.layout.projectDirectory.file("local.properties"))
    .asText.orElse("").map { contents -> Properties().apply { load(contents.reader()) } }
val regularFontPath = providers.gradleProperty("glasmio.symbolFont")
    .orElse(providers.environmentVariable("HYPERPODS_SF_PRO"))
    .orElse(fontProperties.map { it.getProperty("glasmio.symbolFont", "") })
    .map { it.trim() }.filter { it.isNotEmpty() }
val italicFontPath = providers.gradleProperty("glasmio.italicFont")
    .orElse(providers.environmentVariable("HYPERPODS_SF_PRO_ITALIC"))
    .orElse(fontProperties.map { it.getProperty("glasmio.italicFont", "") })
    .map { it.trim() }.filter { it.isNotEmpty() }
val generateHyperPodsFontAssets = tasks.register<GenerateHyperPodsFontAssets>("generateHyperPodsFontAssets") {
    sourceFont.set(layout.file(regularFontPath.map { File(it) }))
    sourceItalicFont.set(layout.file(italicFontPath.map { File(it) }))
    outputDirectory.set(layout.buildDirectory.dir("generated/hyperpods-fonts/assets"))
}
androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(generateHyperPodsFontAssets, GenerateHyperPodsFontAssets::outputDirectory)
}
