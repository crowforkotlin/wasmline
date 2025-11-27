plugins {
  id("com.android.library")
}

android {
  namespace = Config.getNamespace(project)
  compileSdk = libsEx.versions.`android-compileSdk`.requiredVersion.toInt()

  sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
  sourceSets["main"].res.srcDirs("src/androidMain/res")
  sourceSets["main"].resources.srcDirs("src/commonMain/resources")

  defaultConfig {
    minSdk = libsEx.versions.`android-minSdk`.requiredVersion.toInt()
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}
