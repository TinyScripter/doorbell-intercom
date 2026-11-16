plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Versionsschema: versionCode = major * 1000 + minor * 100 + patch * 10, die letzte Stelle
// bleibt frei für Nachbauten derselben Version. Minor und Patch daher jeweils höchstens 9.
val appVersion = "1.0.1"

fun versionCodeOf(version: String): Int {
    val (major, minor, patch) = version.split(".").map(String::toInt)
    require(minor in 0..9 && patch in 0..9) { "Minor und Patch müssen 0..9 sein (Versionsschema): $version" }
    return major * 1000 + minor * 100 + patch * 10
}

android {
    namespace = "de.kleinabersmart.doorbellintercom"
    // core-telecom 1.1 verlangt mindestens 36.1; targetSdk (Laufzeitverhalten) bleibt bei 36.
    compileSdk = 37

    defaultConfig {
        applicationId = "de.kleinabersmart.doorbellintercom"
        minSdk = 26
        targetSdk = 36
        versionCode = versionCodeOf(appVersion)
        versionName = appVersion
    }

    // play: nutzt Google Play Services (ML-Kit-QR-Scanner, keine Kamera-Berechtigung).
    // free: ohne Play Services (zxing-android-embedded, mit Kamera-Berechtigung), z. B. für F-Droid.
    // Beide haben eine eigene applicationId und lassen sich parallel installieren.
    flavorDimensions += "store"
    productFlavors {
        create("play") {
            dimension = "store"
            applicationIdSuffix = ".play"
            versionNameSuffix = "-play"
        }
        create("free") {
            dimension = "store"
            applicationIdSuffix = ".free"
            versionNameSuffix = "-free"
        }
    }

    // Werte kommen aus ~/.gradle/gradle.properties (nicht im Repo). Fehlen sie, entsteht ein
    // unsigniertes Release-APK (z. B. für F-Droid, das selbst signiert) statt eines Fehlers
    // erst beim Packen.
    val releaseSigning = providers.gradleProperty("releaseStoreFile").orNull?.let { storeFilePath ->
        signingConfigs.create("release") {
            storeFile = file(storeFilePath)
            storePassword = providers.gradleProperty("releaseStorePassword").get()
            keyAlias = providers.gradleProperty("intercomKeyAlias").get()
            keyPassword = providers.gradleProperty("intercomKeyPassword").get()
        }
    }

    buildTypes {
        debug {
            // Parallel zur Release-App installierbar (eigener Name und Icon in src/debug)
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // R8: HiveMQ bringt Netty und RxJava mit, ohne Shrinking ist das APK unnötig groß.
            // Eigene Regeln (HiveMQ/Netty, Log.d/v entfernen) in proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseSigning != null) {
                signingConfig = releaseSigning
            } else {
                logger.lifecycle("releaseStoreFile nicht gesetzt – Release-APK wird unsigniert gebaut")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        // viewBinding bleibt für die Einstellungs-Bildschirme, Anruf- und Hauptbildschirm laufen in Compose.
        viewBinding = true
        compose = true
    }

    androidResources {
        // Erzeugt locales_config.xml aus den vorhandenen values-<sprache>-Ordnern, damit
        // Android ab 13 die App-Sprache in den Systemeinstellungen anbietet. Neue Sprachen
        // brauchen nur ein weiteres values-<sprache>/strings.xml.
        generateLocaleConfig = true
    }

    lint {
        // Fehler brechen jeden Build ab, auch Release-Builds; Ausnahmen mit Begründung in lint.xml.
        abortOnError = true
        checkReleaseBuilds = true
        lintConfig = file("lint.xml")
    }

    testOptions {
        unitTests {
            // Robolectric braucht die App-Ressourcen (Strings, Manifest).
            isIncludeAndroidResources = true
            // android.util.Log in reinen JVM-Tests (Baichuan) liefert 0 statt zu werfen.
            isReturnDefaultValues = true
            all {
                // Robolectric (SDK 36) greift per Reflection auf jdk.internal.access zu, das neuere
                // JDKs (hier 25) sonst nicht freigeben: "Failed to interact with raw FileDescriptor".
                it.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
            }
        }
    }

    packaging {
        resources {
            // Mehrere Netty-Jars (transitive Abhängigkeit von hivemq-mqtt-client)
            // bringen alle dieselben META-INF-Metadaten mit, die sich nicht
            // deduplizieren lassen – werden nicht zur Laufzeit gebraucht.
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
            excludes += "META-INF/DEPENDENCIES"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    // Jetpack Compose für Anruf- und Hauptbildschirm; Versionen über die BOM.
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(platform(libs.androidx.compose.bom))
    debugImplementation(libs.androidx.compose.ui.tooling)
    // Registriert die leere ComponentActivity für Compose-UI-Tests (Robolectric).
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Direkter RTSP-Video-/Audiozugriff auf die Kamera – Googles offizielles
    // ExoPlayer-RTSP-Modul (offener Standard, kein Reverse-Engineering).
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.rtsp)
    // Für PlayerView (seitenverhältnis-korrekte Video-Darstellung)
    implementation(libs.androidx.media3.ui)

    // Reiner Java/Kotlin-MQTT-Client, kein veralteter Android-Service-Wrapper nötig
    implementation(libs.hivemq.mqtt.client)

    // Pushweg über UnifiedPush (z. B. mit der ntfy-App als Verteiler): kein eigenes
    // Cloud-Projekt nötig, Home Assistant schickt einfach ein HTTP-POST an die Endpoint-URL.
    implementation(libs.unifiedpush.connector)

    // Klingeln als selbstverwalteter Anruf im System (optional, Einstellung "Als Anruf einbinden"):
    // Halten bei einem Telefonat, Annehmen/Auflegen über Uhr und Headset, Audio-Routing.
    implementation(libs.androidx.core.telecom)

    implementation(libs.kotlinx.coroutines.android)
    // lifecycleScope: Coroutines der Activities enden mit dem Bildschirm.
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // QR-Code für den Einstellungs-Transfer: zxing erzeugt ihn (alle Varianten); eingelesen
    // wird er je nach Variante über den ML-Kit-Scanner (play) oder zxing-embedded (free).
    implementation(libs.zxing.core)
    "playImplementation"(libs.play.services.code.scanner)
    "freeImplementation"(libs.zxing.android.embedded)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core.ktx)
    testImplementation(libs.kotlinx.coroutines.test)
    // Compose-Screenshot- und UI-Tests unter Robolectric; Version über die BOM.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
}
