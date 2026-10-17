plugins {
    id("com.android.application")
}

android {
    namespace = "de.kleinabersmart.doorbellintercom"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.kleinabersmart.doorbellintercom"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
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

    signingConfigs {
        create("release") {
            // Werte kommen aus ~/.gradle/gradle.properties (nicht im Repo)
            val storeFilePath = providers.gradleProperty("releaseStoreFile").orNull
            if (storeFilePath != null) {
                storeFile = file(storeFilePath)
                storePassword = providers.gradleProperty("releaseStorePassword").get()
                keyAlias = providers.gradleProperty("intercomKeyAlias").get()
                keyPassword = providers.gradleProperty("intercomKeyPassword").get()
            }
        }
    }

    buildTypes {
        debug {
            // Parallel zur Release-App installierbar (eigener Name und Icon in src/debug)
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }

    androidResources {
        // Erzeugt locales_config.xml aus den vorhandenen values-<sprache>-Ordnern, damit
        // Android ab 13 die App-Sprache in den Systemeinstellungen anbietet. Neue Sprachen
        // brauchen nur ein weiteres values-<sprache>/strings.xml.
        generateLocaleConfig = true
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
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")

    // Direkter RTSP-Video-/Audiozugriff auf die Kamera – Googles offizielles
    // ExoPlayer-RTSP-Modul (offener Standard, kein Reverse-Engineering).
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.8.0")
    // Für PlayerView (seitenverhältnis-korrekte Video-Darstellung)
    implementation("androidx.media3:media3-ui:1.8.0")

    // Reiner Java/Kotlin-MQTT-Client, kein veralteter Android-Service-Wrapper nötig
    implementation("com.hivemq:hivemq-mqtt-client:1.3.10")

    // Pushweg über UnifiedPush (z. B. mit der ntfy-App als Verteiler): kein eigenes
    // Cloud-Projekt nötig, Home Assistant schickt einfach ein HTTP-POST an die Endpoint-URL.
    implementation("org.unifiedpush.android:connector:3.3.5")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // QR-Code für den Einstellungs-Transfer: zxing erzeugt ihn (alle Varianten); eingelesen
    // wird er je nach Variante über den ML-Kit-Scanner (play) oder zxing-embedded (free).
    implementation("com.google.zxing:core:3.5.4")
    "playImplementation"("com.google.android.gms:play-services-code-scanner:16.1.0")
    "freeImplementation"("com.journeyapps:zxing-android-embedded:4.3.0")
}
