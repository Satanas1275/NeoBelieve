# Regles officielles NewPipeExtractor : le moteur JS (Rhino) sert a dechiffrer
# les signatures de flux YouTube, il est trouve par reflexion donc R8 doit le garder.
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.tools.**

-keep class org.schabi.newpipe.extractor.** { *; }
