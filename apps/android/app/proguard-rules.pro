# DropFlow Android — custom ProGuard/R8 rules.
# Release builds currently run with minification disabled; these rules keep
# Kotlin metadata and kotlinx.coroutines defaults sane if minification is
# enabled later.
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**
