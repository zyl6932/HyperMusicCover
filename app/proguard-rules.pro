# R8 for a module that is loaded by somebody else.
#
# Nothing outside this file reaches our code the normal way: LSPosed reads the class name out of
# META-INF/xposed/java_init.list and instantiates it reflectively, so from R8's point of view the whole module
# is unreachable and would otherwise be stripped down to nothing.

# The entry point named in META-INF/xposed/java_init.list, and the wallpaper-process half it
# hands off to. attachFramework is called on the entry instance by name, from the framework.
# Members are kept as well: the hook callbacks are only ever called by the framework.
-keep class com.os4.musiccover.Main { *; }
-keep class com.os4.musiccover.WallpaperProbe { *; }

# The Xposed API is compileOnly - it exists in the host process at runtime and is deliberately
# not packaged, so R8 sees dangling references to it.
-dontwarn io.github.libxposed.**
-keep class io.github.libxposed.api.** { *; }

# The SuperLyric API ships consumer rules for the @Wire fields and the AIDL stubs, but not for
# the hidden android.os.ServiceManager it looks the service up through - not on the compile
# classpath, present at runtime inside SystemUI, so the dangling reference is expected.
-dontwarn android.os.ServiceManager
#
# The rest of it is kept whole because two paths are reflective and would only fail in release:
# WireCodec builds every struct with getDeclaredConstructor().newInstance(), which no keep rule
# covers, and the legacy 3.x transport reads SuperLyricData back off a Parcel by class name. Both
# degrade quietly - the first throws WireFormatException on every frame, the second finds nothing.
-keep class com.hchen.superlyricapi.** { *; }

# ColorOS's pickup plugin (assets/coloros/pcr_plugin.apk) is loaded at runtime into its own class
# loader whose parent is this module's, and it resolves Gson and the Kotlin stdlib out of this dex
# by name - we dex them because the plugin's OEM host supplies them and the plugin bundles neither.
#
# Gson: nothing in this module's own code mentions it, so R8 sees no reference to it at all and
# would drop it; and -repackageclasses below would move whatever survived, which breaks the name
# lookup just as completely. Its TypeToken path is reflective, so the whole package has to stay.
-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**
-keepattributes Signature, *Annotation*

# Kotlin stdlib: this module is Kotlin, so R8 keeps the parts its own code calls - but the plugin
# calls its own set (CollectionsKt.addAll, StringsKt.split$default, kotlin.jvm.internal.Intrinsics
# and friends). Shrunk to our usage, those would be gone and the plugin would die on the first
# call with a NoSuchMethodError that only shows up on a device.
-keep class kotlin.** { *; }

-repackageclasses
