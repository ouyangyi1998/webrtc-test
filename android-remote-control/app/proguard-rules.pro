# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Keep WebRTC classes
-keep class org.webrtc.** { *; }
-keep class com.example.remotecontrol.** { *; }
