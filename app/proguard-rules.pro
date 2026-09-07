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

# release 构建剥离 v/d 级日志调用（排查期随手打点零成本）
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
