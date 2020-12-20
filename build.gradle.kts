plugins {
    id("org.jetbrains.kotlin.js") version "1.4.21"
    kotlin("plugin.serialization") version "1.4.21"
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    maven { setUrl("https://dl.bintray.com/kotlin/kotlin-eap") }
    maven("https://kotlin.bintray.com/kotlin-js-wrappers/")
    mavenCentral()
    jcenter()
}

kotlin {
    js {
        browser {}
        binaries.executable()
    }
}

dependencies {
    implementation(kotlin("stdlib-js"))

    implementation("org.jetbrains:kotlin-react:17.0.0-pre.132-kotlin-1.4.21")
    implementation("org.jetbrains:kotlin-react-dom:17.0.0-pre.132-kotlin-1.4.21")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.0.1")
    implementation(npm("react", "17.0.0"))
    implementation(npm("react-dom", "17.0.0"))
}