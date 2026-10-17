plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.tyquawn.atc"
    compileSdk = 34
    defaultConfig { applicationId = "com.tyquawn.atc"; minSdk = 26; targetSdk = 34; versionCode = 5; versionName = "0.5" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests { isIncludeAndroidResources = true; isReturnDefaultValues = true } }
}
dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.12.2")
}
