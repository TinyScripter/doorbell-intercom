# Eigene R8-Regeln zusätzlich zu proguard-android-optimize.txt und den Consumer-Regeln
# der Bibliotheken.

# HiveMQ-MQTT-Client: Netty und JCTools greifen per Reflection auf Felder zu
# (Empfehlung aus der HiveMQ-Doku für Android).
-keepclassmembernames class io.netty.** { *; }
-keepclassmembers class org.jctools.** { *; }

# Optionale Abhängigkeiten von Netty bzw. HiveMQ, die zur Laufzeit geprüft und hier nicht
# genutzt werden: Epoll (Linux-Server), WebSocket- und Proxy-Transport, natives OpenSSL bzw.
# alternative TLS-Provider (die App spricht MQTT ohne TLS) und fremde Logging-Frameworks.
-dontwarn io.netty.channel.epoll.**
-dontwarn io.netty.handler.codec.http.**
-dontwarn io.netty.handler.proxy.**
-dontwarn io.netty.internal.tcnative.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.eclipse.jetty.alpn.**
-dontwarn org.eclipse.jetty.npn.**
-dontwarn org.apache.log4j.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.slf4j.**

# Debug- und Verbose-Logs im Release entfernen; Info, Warnungen und Fehler bleiben
# (Ring-Log-Diagnose per logcat).
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
