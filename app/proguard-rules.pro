# sherpa-onnx: data-классы конфигов читаются из нативного кода через JNI по
# именам полей — обфускация их ломает.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# onnxruntime
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# ML Kit подтягивает модели через рефлексию.
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
