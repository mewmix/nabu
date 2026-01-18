# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

-dontwarn com.gemalto.jp2.JP2Decoder
-dontwarn org.tensorflow.lite.gpu.**
-dontwarn org.apache.pdfbox.rendering.PDFRenderer
-dontwarn org.apache.pdfbox.pdmodel.font.FileSystemFontProvider

# ONNX Runtime rules
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# Gson rules
-keep class com.google.gson.** { *; }
-keepattributes Signature
-keepattributes *Annotation*

# Keep BuildConfig
-keep class com.example.nabu.BuildConfig { *; }
