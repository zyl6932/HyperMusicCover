# R8 for a module that is loaded by somebody else.
#
# Nothing outside this file reaches our code the normal way: LSPosed reads the class name out of
# assets/xposed_init and instantiates it reflectively, so from R8's point of view the whole module
# is unreachable and would otherwise be stripped down to nothing.

# The entry point named in assets/xposed_init, and the wallpaper-process half it hands off to.
# Members are kept as well: the hook callbacks are only ever called by the framework.
-keep class com.os4.musiccover.Main { *; }
-keep class com.os4.musiccover.WallpaperProbe { *; }

# The Xposed API is compileOnly - it exists in the host process at runtime and is deliberately
# not packaged, so R8 sees dangling references to it.
-dontwarn de.robv.android.xposed.**
-keep class de.robv.android.xposed.** { *; }
-keep class de.robv.android.xposed.callbacks.** { *; }
