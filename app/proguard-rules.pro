# Module code is also reached by the Xposed loader, vendor hooks, reflection and JNI.
# Preserve it verbatim; only remove unused dependency code and Android resources.
-keep class !com.rikumi.colorosmod.R,!com.rikumi.colorosmod.R$*,com.rikumi.colorosmod.** { *; }
-dontobfuscate
-dontoptimize
