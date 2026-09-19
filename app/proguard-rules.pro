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

# ONNX Runtime：官方 AAR 的 proguard 规则只覆盖遥测类，JNI 入口必须整体保留
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# zxing：生成二维码走 QRCodeWriter 内部查找表/枚举，R8 全程序优化可能裁掉，
# release 包点「生成二维码」会直接崩。embedded 扫码侧自带 consumer 规则，生成侧没有。
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

