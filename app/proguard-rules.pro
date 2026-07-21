# Keep Oboe/JNI native bridge entry points.
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep our JNI-facing classes and their members (called by name from C++).
-keep class com.audiopro.djmrec.audio.** { *; }
-keep class com.audiopro.djmrec.usb.** { *; }

# google-api-client / google-api-services-drive: JSON (de)serialization is reflection-based
# (GenericJson field lookup by name), so model classes must keep their fields, and the libraries
# reference some annotation/transport classes not present on Android that are safe to warn-away.
-keepclassmembers class com.google.api.services.drive.model.** { *; }
-keepclassmembers class com.google.api.client.** { *; }
-dontwarn com.google.api.client.**
-dontwarn org.apache.http.**
-dontwarn javax.annotation.**
-dontwarn org.joda.time.**

# OkHttp/Okio (SoundCloud uploads) and Tink (androidx.security EncryptedSharedPreferences) both
# reference optional platform/crypto-provider classes not present on Android by default.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn com.google.crypto.tink.**
