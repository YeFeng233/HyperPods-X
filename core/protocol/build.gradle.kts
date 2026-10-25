plugins { id("org.jetbrains.kotlin.jvm") }
kotlin { jvmToolchain(21) }
dependencies { testImplementation(kotlin("test-junit")) }
