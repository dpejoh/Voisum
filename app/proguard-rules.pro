# ProGuard / R8 rules for Voisum

# Keep Retrofit interfaces
-keep,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }

# Keep Gson models
-keep class com.voisum.app.api.** { *; }

# Keep Room entities
-keep class com.voisum.app.history.HistoryEntity { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Keep accessibility service
-keep class com.voisum.app.accessibility.VoisumAccessibilityService { *; }
