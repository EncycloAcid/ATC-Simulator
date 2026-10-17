plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.tyquawn.atc"
    compileSdk = 34
    defaultConfig { applicationId = "com.tyquawn.atc"; minSdk = 26; targetSdk = 34; versionCode = 3; versionName = "0.3" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
