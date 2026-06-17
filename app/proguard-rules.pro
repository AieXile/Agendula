# Keep Hilt-generated classes
-keep class dagger.hilt.** { *; }
-keep @dagger.hilt.android.HiltAndroidApp class *

# Compose Compiler may keep its own; defaults are fine
-dontwarn org.jetbrains.annotations.**
