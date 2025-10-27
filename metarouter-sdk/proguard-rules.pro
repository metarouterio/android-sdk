# ProGuard rules for MetaRouter Android SDK
# These apply when building the SDK itself (currently disabled via isMinifyEnabled = false)

# Keep public API
-keep public interface com.metarouter.analytics.AnalyticsInterface { *; }
-keep public class com.metarouter.analytics.InitOptions { *; }

# Keep all type classes for kotlinx.serialization
-keep class com.metarouter.analytics.types.** { *; }

# Keep utility classes with public methods
-keep public class com.metarouter.analytics.utils.** { *; }

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# Keep serializer classes
-keep,includedescriptorclasses class com.metarouter.analytics.**$$serializer { *; }
-keepclassmembers class com.metarouter.analytics.** {
    *** Companion;
}
-keepclasseswithmembers class com.metarouter.analytics.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# If using R8 full mode
-keepclassmembers,allowobfuscation class * {
  @kotlinx.serialization.SerialName <fields>;
}
