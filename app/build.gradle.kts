import java.util.Properties

plugins {
    alias(libs.plugins.agp.app)
}

val releaseKeystoreProperties = Properties()
val releaseKeystorePropertiesFile = rootProject.file("keystore.properties")
if (releaseKeystorePropertiesFile.exists()) {
    releaseKeystorePropertiesFile.inputStream().use(releaseKeystoreProperties::load)
}

android {
    namespace = "dev.chaoxingdeadline"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "dev.chaoxingdeadline"
        minSdk = 26
        targetSdk = 36
        versionCode = 22
        versionName = "1.12.0"
    }

    signingConfigs {
        if (releaseKeystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(releaseKeystoreProperties.getProperty("storeFile"))
                storePassword = releaseKeystoreProperties.getProperty("storePassword")
                keyAlias = releaseKeystoreProperties.getProperty("keyAlias")
                keyPassword = releaseKeystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles("proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
            excludes += "**"
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    compileOnly(libs.libxposed.api)
    implementation(files("libs/service-102.0.0-patched.aar"))
    implementation(files("libs/interface-102.0.0-patched.aar"))

    // DeadlineParser is plain string/regex work, so it is testable without a device.
    testImplementation("junit:junit:4.13.2")
    // android.jar ships org.json as a throwing stub; a real one makes the JSON branch testable.
    testImplementation("org.json:json:20240303")
    // DeadlineStore (SQLite + SharedPreferences) needs a device-like environment.
    testImplementation("org.robolectric:robolectric:4.14.1")
}
