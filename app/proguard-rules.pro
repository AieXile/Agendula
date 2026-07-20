# Keep Hilt-generated classes
-keep class dagger.hilt.** { *; }
-keep @dagger.hilt.android.HiltAndroidApp class *

# Room instantiates its generated <Database>_Impl reflectively through a no-arg
# constructor. R8 under AGP 9 keeps the class but prunes that constructor, since
# nothing calls it directly — Room then throws InstantiationException, reported
# as "Failed to create an instance of ...". We pull Room in transitively via
# Glance -> WorkManager, whose WorkDatabase is built by WorkManagerInitializer
# at startup, so the app died on launch in every minified build (issue #1).
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# WorkManager likewise looks its workers up by name and calls this constructor
# reflectively — same pruning, but it only bites once a worker actually runs
# (Glance's widget updates), so keep it explicitly rather than wait for it.
-keep class * extends androidx.work.ListenableWorker {
    <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Compose Compiler may keep its own; defaults are fine
-dontwarn org.jetbrains.annotations.**
