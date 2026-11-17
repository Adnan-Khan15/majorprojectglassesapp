# LiteRT-LM ships no consumer rules and calls back into these classes from native
# code (JNI) and via Gson; keep the whole package as-is.
-keep class com.google.ai.edge.litertlm.** { *; }
-keepclassmembers class * { native <methods>; }
# Gson reflection over LiteRT-LM's JSON types.
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod
-dontwarn com.google.ai.edge.litertlm.**
