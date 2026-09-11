# JNA and the generated UniFFI bindings resolve types reflectively, so R8 must
# not rename or strip them -- doing so breaks the native call at runtime with
# an UnsatisfiedLinkError that is very hard to trace back here.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class uniffi.filemanager_core.** { *; }
-dontwarn java.awt.**
