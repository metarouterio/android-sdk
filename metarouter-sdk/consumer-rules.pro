# Consumer ProGuard rules for MetaRouter Android SDK
# These rules are automatically applied to apps that use this SDK

# Keep public API - apps need to call these
-keep public interface com.metarouter.analytics.AnalyticsInterface { *; }
-keep public class com.metarouter.analytics.InitOptions { *; }

# Keep all type classes for kotlinx.serialization
# These must not be obfuscated or serialization will break
-keep class com.metarouter.analytics.types.** { *; }

# Keep serialization annotations
-keepattributes *Annotation*, InnerClasses

# Keep Kotlin Serialization infrastructure
-keep,includedescriptorclasses class com.metarouter.analytics.**$$serializer { *; }
-keepclassmembers class com.metarouter.analytics.** {
    *** Companion;
}
-keepclasseswithmembers class com.metarouter.analytics.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# R8 full mode support
-keepclassmembers,allowobfuscation class * {
  @kotlinx.serialization.SerialName <fields>;
}
