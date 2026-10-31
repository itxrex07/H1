import java.io.File
import java.net.URL
import java.security.MessageDigest

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
}

// Xray-core is supplied by the upstream AndroidLibXrayLite AAR. Download a pinned,
// SHA-256-verified release at build time instead of committing a 60 MB native binary.
val libv2rayAar = layout.buildDirectory.file("generated/xray/libv2ray.aar")
val downloadLibv2ray by tasks.registering {
  outputs.file(libv2rayAar)
  doLast {
    val target = libv2rayAar.get().asFile
    val expectedSha256 = "e56ec3b06485fe7b37af26948673951edf719c50d6bbc618b8539045952adde4"
    fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
      .digest(file.readBytes()).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    if (target.isFile && sha256(target) == expectedSha256) return@doLast
    target.parentFile.mkdirs()
    val temporary = File(target.parentFile, "libv2ray.aar.download")
    URL("https://github.com/2dust/AndroidLibXrayLite/releases/download/v26.10.21/libv2ray.aar")
      .openStream().use { input -> temporary.outputStream().use { output -> input.copyTo(output) } }
    check(sha256(temporary) == expectedSha256) { "The downloaded Xray AAR failed SHA-256 verification." }
    check(temporary.renameTo(target)) { "Could not install the verified Xray AAR." }
  }
}
tasks.named("preBuild").configure { dependsOn(downloadLibv2ray) }

android {
  namespace = "com.hollow.app"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.hollow.app"
    minSdk = 24
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(files(libv2rayAar))
  compileOnly(files("libs/xposed-api-82.jar"))
  implementation(platform(libs.androidx.compose.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  // implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  // implementation(libs.coil.compose)
  // implementation(libs.converter.moshi)

  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  // implementation(libs.moshi.kotlin)
  // implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  // implementation(libs.retrofit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  // "ksp"(libs.moshi.kotlin.codegen)
}
