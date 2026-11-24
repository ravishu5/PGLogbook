# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
-dontnote kotlinx.serialization.**
-keepclassmembers class org.mdpeds.logbook.** {
    *** Companion;
}
-keepclasseswithmembers class org.mdpeds.logbook.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class org.mdpeds.logbook.**$$serializer { *; }

# Retrofit service interfaces
-keep,allowobfuscation interface org.mdpeds.logbook.data.remote.** { *; }
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
