# Keep Hilt-generated classes
-keep class dagger.hilt.** { *; }
-keep @dagger.hilt.android.HiltAndroidApp class *

# Room instantiates its generated <Database>_Impl reflectively through a no-arg
# constructor. R8 under AGP 9 keeps the class but prunes that constructor, since
# nothing calls it directly — Room then throws InstantiationException, reported
# as "Failed to create an instance of ...". This first bit us through a
# transitive Room (Glance -> WorkManager -> WorkDatabase, built at startup:
# issue #1); Room is now also our own task store, so the rule matters more,
# not less — TasksDatabase is built on the first store read.
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# Compose Compiler may keep its own; defaults are fine
-dontwarn org.jetbrains.annotations.**

# dnsjava (CalDAV SRV/TXT discovery) references JNA, JNDI, Lombok and SLF4J
# bindings that only exist on desktop JVMs; its Android resolver needs none.
-dontwarn com.sun.jna.**
-dontwarn javax.naming.**
-dontwarn lombok.Generated
-dontwarn org.slf4j.impl.StaticLoggerBinder
