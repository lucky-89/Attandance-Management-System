# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.finowise.hr.core.**$$serializer { *; }
-keepclassmembers class com.finowise.hr.core.** { *** Companion; }
-keepclasseswithmembers class com.finowise.hr.core.** { kotlinx.serialization.KSerializer serializer(...); }
