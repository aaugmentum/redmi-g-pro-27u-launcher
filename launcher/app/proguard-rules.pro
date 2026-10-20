# Shrink and optimise, but keep class and method names: the size win comes from dropping unused
# AndroidX code, not from renaming, and readable stack traces matter more in a sideloaded app
# where crash reports arrive as screenshots.
-dontobfuscate

# sherpa-onnx (VoiceInput): its native code reads the config classes' fields by name.
-keep class com.k2fsa.sherpa.onnx.** { *; }
