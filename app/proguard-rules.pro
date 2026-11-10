# UVC USB callbacks and JNI bridge types must remain available in minified release builds.
-keep class com.herohan.uvcapp.** { *; }
-keep class com.serenegiant.usb.** { *; }
-keep class com.serenegiant.uvc.** { *; }

# RootEncoder uses runtime-selected encoder and stream implementations.
-keep class com.pedro.** { *; }

-keepclassmembers class * implements com.serenegiant.usb.IButtonCallback { *; }
-keepclassmembers class * implements com.serenegiant.usb.IFrameCallback { *; }
-keepclassmembers class * implements com.serenegiant.usb.IStatusCallback { *; }
