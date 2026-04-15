# Add project specific ProGuard rules here.

# Keep TFLite classes
-keep class org.tensorflow.** { *; }
-keep class org.tensorflow.lite.** { *; }
-keepclassmembers class org.tensorflow.lite.** { *; }

# Keep CameraX classes
-keep class androidx.camera.** { *; }

# Keep our own classes (useful if minification is turned on in release)
-keep class com.homeai.assistant.** { *; }

# Keep Kotlin coroutines
-keepnames class kotlinx.coroutines.** { *; }
