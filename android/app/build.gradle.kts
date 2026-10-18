import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.jetbrains.kotlin.android)
  alias(libs.plugins.compose.compiler)
}

val localProperties =
    Properties().apply {
      val file = rootDir.resolve("local.properties")
      if (file.exists()) file.inputStream().use { load(it) }
    }

// web/viewer.html is the single source of truth for the desktop viewer; copy it into
// the generated assets so the phone can serve it at GET /.
val copyViewer =
    tasks.register<Copy>("copyViewer") {
      from(rootDir.resolve("../web/viewer.html"))
      into(layout.buildDirectory.dir("generated/viewer-assets"))
    }

android {
  namespace = "dev.glasscast"
  compileSdk = 36

  defaultConfig {
    applicationId = "dev.glasscast.app"
    minSdk = 31
    targetSdk = 36
    versionCode = 1
    versionName = "0.1.0"

    // Meta Wearables Device Access Toolkit credentials (see local.properties.example).
    manifestPlaceholders["mwdat_application_id"] =
        localProperties.getProperty("mwdat_application_id", "")
    manifestPlaceholders["mwdat_client_token"] =
        localProperties.getProperty("mwdat_client_token", "")
  }

  sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/viewer-assets"))

  buildFeatures { compose = true }

  buildTypes {
    release {
      isMinifyEnabled = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

tasks.named("preBuild") { dependsOn(copyViewer) }

dependencies {
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.material3)
  implementation(libs.androidx.material.icons.extended)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.mwdat.core)
  implementation(libs.mwdat.camera)
  implementation(libs.zxing.core)
}
