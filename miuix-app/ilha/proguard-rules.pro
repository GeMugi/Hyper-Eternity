# Os hooks do Hyper Eternity entram por reflexão (LSPosed, java_init.list, Interface do Sistema)
-keep class com.ilha.headsup.** { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
# API do LSPosed e classes internas do Android/HyperOS existem só no aparelho
-dontwarn **
