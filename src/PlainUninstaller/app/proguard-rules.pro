# Shizuku: the provider is named in the manifest (AAPT keeps it), but keep the class and its binder
# hand-off explicitly so a stricter R8 can't break it.
-keep class rikka.shizuku.ShizukuProvider { *; }
# Shiz.run calls the private Shizuku.newProcess(String[], String[], String) by reflection.
-keepclassmembers class rikka.shizuku.Shizuku {
    private static rikka.shizuku.ShizukuRemoteProcess newProcess(java.lang.String[], java.lang.String[], java.lang.String);
}
-keep class rikka.shizuku.ShizukuRemoteProcess { *; }
# AIDL interfaces travel over Binder: keep their stubs/proxies and descriptors.
-keep class moe.shizuku.server.** { *; }
-keep class rikka.shizuku.server.** { *; }
# androidx.annotation is excluded from the build (annotations only).
-dontwarn androidx.annotation.**
