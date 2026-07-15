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

# ------------------------------------------------------------------
# Keep rules for reflection / JNI-heavy dependencies used by the Clock
# 3D timezone globe and JSON/geometry handling. These libraries do not
# ship reliable consumer rules, so R8 could otherwise strip classes
# that are only reached from native code or reflection.
# ------------------------------------------------------------------

# Filament + glTF I/O (loaded via JNI; classes referenced from native)
-keep class com.google.android.filament.** { *; }
-keep class com.google.android.filament.gltfio.** { *; }
-dontwarn com.google.android.filament.**

# SceneView
-keep class io.github.sceneview.** { *; }
-dontwarn io.github.sceneview.**

# ARCore
-keep class com.google.ar.** { *; }
-dontwarn com.google.ar.**

# JTS geometry (WKT/GeoJSON parsing for timezone boundaries)
-keep class org.locationtech.jts.** { *; }
-dontwarn org.locationtech.jts.**

# Gson uses generic type information via TypeToken (List<String> / List<Long>)
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken