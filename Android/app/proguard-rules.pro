# ===========================================================================
# R8 rules
# ===========================================================================
# kotlinx.serialization resolves serializers reflectively through generated
# $$serializer classes and Companion.serializer(). R8 cannot see those uses, so
# without these the Open-Meteo DTOs shrink away and every fetch fails to parse --
# and it fails *silently*, because the repositories treat a parse error as "no
# data" and keep showing last-good values. Exactly the kind of bug that only
# appears in the release build, on the wall, a week later.

-keepattributes *Annotation*, InnerClasses

-if @kotlinx.serialization.Serializable class ca.garionhk.cydclock.data.**
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

-if @kotlinx.serialization.Serializable class ca.garionhk.cydclock.data.**
-keepclasseswithmembers class <1>$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class ca.garionhk.cydclock.data.**$$serializer { *; }

# The font assets are loaded by name from assets/, not referenced in code.
-keep class ca.garionhk.cydclock.render.TftFont { *; }
