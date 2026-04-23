# LiteRT-LM uses JNI/reflection to instantiate and throw classes by name.
# Keep the package stable in release builds so the runtime can resolve them.
-keep class com.google.ai.edge.litertlm.** { *; }
