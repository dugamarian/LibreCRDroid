import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseProperties = Properties().apply {
    val file = rootProject.file("release.properties")
    if (file.isFile) {
        file.inputStream().use(::load)
    }
}

fun releaseProperty(name: String, environmentName: String): String? =
    providers.environmentVariable(environmentName).orNull
        ?: releaseProperties.getProperty(name)

val releaseStoreFile = releaseProperty("storeFile", "LIBRECR_RELEASE_STORE_FILE")
val releaseStorePassword = releaseProperty("storePassword", "LIBRECR_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = releaseProperty("keyAlias", "LIBRECR_RELEASE_KEY_ALIAS")
val releaseKeyPassword = releaseProperty("keyPassword", "LIBRECR_RELEASE_KEY_PASSWORD")
val hasReleaseSigning = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

android {
    namespace = "re.abbot.librecr.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "re.abbot.librecr.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 5
        versionName = "0.1.6"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        // Material 3 Expressive (MaterialExpressiveTheme, expressive components) and the
        // foundation pager/zoom APIs the chart uses are still opt-in in material3 1.4.x.
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
        )
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    lint {
        disable += "NullSafeMutableLiveData"
    }
}

dependencies {
    implementation(project(":protocol"))

    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("com.google.android.gms:play-services-wearable:18.2.0")

    // Material 3 Expressive ships in material3 1.4.0 (pinned by this BOM).
    val composeBom = platform("androidx.compose:compose-bom:2025.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.glance:glance-appwidget:1.2.0")
    implementation("androidx.car.app:app:1.7.0")
    implementation("io.bimmergestalt.idriveconnectkit:idriveconnectkit:0.8")
    implementation("io.bimmergestalt:IDriveConnectKitAndroid:0.8")
    implementation("androidx.navigation:navigation-compose:2.9.8")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    // Real org.json so JSONObject works in JVM unit tests (android.jar's is a throwing stub).
    testImplementation("org.json:json:20240303")
}
