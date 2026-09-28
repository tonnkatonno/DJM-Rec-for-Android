import java.io.FileInputStream
import java.util.Properties
import com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
}

// ── Keystore helpers (top-level so they can be used by signingConfigs) ─────
// Release signing is configured only when the gitignored keystore.properties exists.
fun loadProperties(path: String): Properties = Properties().apply {
    val source = rootProject.file(path)
    require(source.exists()) { "Missing $path" }
    FileInputStream(source).use(::load)
}

val appVersion = loadProperties("version.properties")
val keystoreFile = rootProject.file("keystore.properties")
val keystore = if (keystoreFile.exists()) loadProperties("keystore.properties") else null
val streamingFile = rootProject.file("streaming.properties")
val streaming = if (streamingFile.exists()) loadProperties("streaming.properties") else null
val twitchClientId = providers.environmentVariable("TWITCH_CLIENT_ID").orNull
    ?: streaming?.getProperty("TWITCH_CLIENT_ID").orEmpty()
// OAuth client IDs are public identifiers. Google still authenticates Android builds using the
// package name and signing certificate registered against each ID in Google Cloud.
val googleLocalClientId =
    "333115759527-9i5hsmubo1up8d7qvqjbgfm2ur9inkvl.apps.googleusercontent.com"
val googlePublicClientId =
    "333115759527-o8poec8lbsa8c98mkpb2k52g7mist9o6.apps.googleusercontent.com"

// OAuth client IDs/secrets (Drive, SoundCloud, ...). File is gitignored; see
// secrets.properties.example for the expected keys. Missing file/keys just leave the
// corresponding BuildConfig field blank -- integrations degrade to "not configured", not a
// build failure, so the project still builds for anyone who hasn't set these up yet.
val secretsFile = rootProject.file("secrets.properties")
val secrets = if (secretsFile.exists()) loadProperties("secrets.properties") else Properties()
fun secret(key: String): String = secrets.getProperty(key, "")

android {
    namespace = "com.audiopro.djmrec"
    compileSdk = 35
    // Pinned so CI (and every dev machine) builds native code against the exact same
    // NDK — avoids "works locally, fails/behaves differently in CI" native-build drift.
    ndkVersion = "26.1.10909125"

    val releaseSigning = keystore?.let { properties ->
        signingConfigs.create("release") {
            storeFile = rootProject.file(properties.getProperty("storeFile"))
            storePassword = properties.getProperty("storePassword")
            keyAlias = properties.getProperty("keyAlias")
            keyPassword = properties.getProperty("keyPassword")
        }
    }

    defaultConfig {
        applicationId = "com.audiopro.djmrec"
        minSdk = 29
        targetSdk = 34
        versionCode = appVersion.getProperty("VERSION_CODE").toInt()
        versionName = appVersion.getProperty("VERSION_NAME")
        buildConfigField("String", "TWITCH_CLIENT_ID", "\"${twitchClientId.replace("\"", "\\\"")}\"")
        buildConfigField("String", "GOOGLE_OAUTH_CLIENT_ID", "\"$googlePublicClientId\"")
        buildConfigField("boolean", "FIREBASE_CONFIGURED", "true")

        // Only ship arm64-v8a: all modern DJ-capable Android hardware (USB-C host + UAC2)
        // is 64-bit ARM. Keeping a single ABI keeps the native audio path easy to validate.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-fexceptions", "-frtti")
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_ARM_NEON=TRUE"
                )
            }
        }

        buildConfigField("String", "GOOGLE_DRIVE_CLIENT_ID", "\"${secret("GOOGLE_DRIVE_CLIENT_ID")}\"")
        buildConfigField("String", "SOUNDCLOUD_CLIENT_ID", "\"${secret("SOUNDCLOUD_CLIENT_ID")}\"")
        buildConfigField("String", "SOUNDCLOUD_CLIENT_SECRET", "\"${secret("SOUNDCLOUD_CLIENT_SECRET")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = releaseSigning
            configure<CrashlyticsExtension> {
                nativeSymbolUploadEnabled = true
            }
        }
        debug {
            isDebuggable = true
            // So debug and release can be installed side-by-side
            applicationIdSuffix = ".debug"
            buildConfigField("String", "GOOGLE_OAUTH_CLIENT_ID", "\"$googleLocalClientId\"")
            buildConfigField("boolean", "FIREBASE_CONFIGURED", "false")
        }
        create("local") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            buildConfigField("String", "GOOGLE_OAUTH_CLIENT_ID", "\"$googleLocalClientId\"")
            buildConfigField("boolean", "FIREBASE_CONFIGURED", "false")
            configure<CrashlyticsExtension> {
                mappingFileUploadEnabled = false
                nativeSymbolUploadEnabled = false
            }
        }
    }

    // ── APK output naming ──────────────────────────────────────────────────
    // Produces versioned APK names from version.properties.
    applicationVariants.all {
        val variant = this
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = "DJM-Rec-for-Android-v${variant.versionName}-${variant.buildType.name}.apk"
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            // We statically link Oboe/FLAC into libdjmrec_audio.so, so only
            // the shared C++ runtime and our own library need to ship.
            useLegacyPackaging = false
        }
        // google-api-client pulls in google-auth-library, which duplicates a few META-INF
        // housekeeping files (not code) across its jars -- harmless to drop one copy.
        resources {
            excludes += listOf(
                "META-INF/INDEX.LIST",
                "META-INF/DEPENDENCIES",
                "META-INF/*.kotlin_module"
            )
        }
    }
}

// Firebase is production-only. Keep debug/local builds isolated from production reports and make
// ordinary contributor builds work without the gitignored Firebase configuration file.
tasks.matching { it.name in setOf("processDebugGoogleServices", "processLocalGoogleServices") }
    .configureEach {
        enabled = false
    }

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("com.google.firebase:firebase-bom:34.18.0"))
    implementation("com.google.firebase:firebase-analytics")
    implementation("com.google.firebase:firebase-crashlytics-ndk")
    testImplementation(kotlin("test"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.activity:activity-compose:1.9.1")

    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.google.android.gms:play-services-auth:21.6.0")
    implementation("com.github.pedroSG94.RootEncoder:library:2.7.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Google Drive upload integration. AuthorizationClient (Identity.getAuthorizationClient) is
    // the current, non-deprecated replacement for GoogleSignInClient.requestScopes -- see
    // developer.android.com/identity/authorization. Drive API access itself (google-api-client /
    // google-api-services-drive) is unaffected by that deprecation.
    implementation("com.google.android.gms:play-services-auth:21.6.0")
    implementation("com.google.api-client:google-api-client-android:2.7.0") {
        exclude(group = "org.apache.httpcomponents")
    }
    implementation("com.google.apis:google-api-services-drive:v3-rev20240914-2.0.0")
    implementation("com.google.http-client:google-http-client-gson:1.44.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // SoundCloud upload integration: OAuth 2.1 + PKCE via Custom Tabs, multipart upload via
    // OkHttp (SoundCloud's confidential-client requirement means we also carry a client secret
    // -- see the code comment on SoundCloudAuthManager -- so its tokens are stored encrypted,
    // unlike Drive's UI-only connected-hint).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.browser:browser:1.8.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
}
