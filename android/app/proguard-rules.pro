# The JNI classes keep their names through android/engine/src/main/jni/jni-keep.pro.
# Stack traces in player reports stay readable: names are shrunk, line numbers kept.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Optional JDOM and SLF4J integrations the game never loads.
-dontwarn org.slf4j.**
-dontwarn org.jdom2.**
-dontwarn javax.xml.**
-dontwarn org.xml.sax.**
-dontwarn org.w3c.dom.**
