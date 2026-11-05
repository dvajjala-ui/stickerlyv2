# Providers, services and the IME are referenced from the manifest and kept by AAPT rules.
# kotlinx.serialization keeps generated serializers via its own consumer rules.
-dontwarn org.slf4j.**

# ML Kit builds its components from manifest metadata and instantiates each registrar reflectively.
# Its consumer rule keeps the classes but, in R8 full mode, not their no-arg constructors, so release
# builds logged "NoSuchMethodException: ...CommonComponentRegistrar.<init> []" (QA build on device).
-keep class * implements com.google.firebase.components.ComponentRegistrar { public <init>(); }
