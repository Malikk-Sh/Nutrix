# Samsung Health Data SDK
-keep class com.samsung.android.sdk.health.data.** { *; }
-dontwarn com.samsung.android.sdk.health.data.**
-dontwarn androidx.databinding.**

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.nutrix.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.nutrix.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# zip4j
-dontwarn net.lingala.zip4j.**
