plugins {
    id("com.android.test")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.lyric.benchmark"
    compileSdk = 35
    defaultConfig {
        minSdk = 29
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["androidx.benchmark.output.enable"] = "true"
    }
    buildTypes {
        create("benchmark") {
            isDebuggable = true
            matchingFallbacks += "release"
        }
    }
    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
androidComponents { beforeVariants(selector().all()) { it.enable = it.buildType == "benchmark" } }
dependencies {
    implementation("androidx.test.ext:junit:1.2.1")
    implementation("androidx.test:runner:1.6.2")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
    implementation("androidx.benchmark:benchmark-macro-junit4:1.3.3")
}
