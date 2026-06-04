# FreeRDP JNI - keep native method declarations
-keep class com.freerdp.freerdpcore.** { *; }

# Keep serializable connection config
-keep class nz.co.ardp.connection.ConnectionConfig { *; }
-keepclassmembers class nz.co.ardp.connection.ConnectionConfig { *; }

# Kotlinx serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers @kotlinx.serialization.Serializable class nz.co.ardp.** {
    *** Companion;
    *** serializer(...);
}
