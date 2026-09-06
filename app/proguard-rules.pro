# 空课混淆规则
# kotlinx-serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.nullclass.importer.** {
    *** Companion;
}
-keepclasseswithmembers class com.nullclass.importer.** {
    kotlinx.serialization.KSerializer serializer(...);
}
