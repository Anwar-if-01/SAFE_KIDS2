# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Firebase and Play Services
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**

# Safe Kids Sensitive Logic
-keep class com.example.safe_kids.auth.** { *; }
-keep class com.example.safe_kids.services.** { *; }
-keep class com.example.safe_kids.data.** { *; }

# Protect Admin PIN and security logic
-keepclassmembers class com.example.safe_kids.ui.admin.** {
    private <fields>;
    public <methods>;
}

# AndroidX and Material Components
-keep class androidx.appcompat.** { *; }
-keep class com.google.android.material.** { *; }

# Compose rules (usually handled by R8 automatically but good to have)
-keep class androidx.compose.** { *; }

# Kotlin Coroutines
-keep class kotlinx.coroutines.** { *; }

# Preserve line numbers for Crashlytics
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile