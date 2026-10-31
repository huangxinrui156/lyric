# Room and kotlinx.serialization supply consumer rules. Retain generated Room
# implementations loaded by naming convention, plus serialization metadata.
-keep class * extends androidx.room.RoomDatabase { *; }
-keep class com.lyric.data.**_Impl { *; }
-keepattributes Signature,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations,AnnotationDefault

# Import/render libraries: conservative keeps for the benchmark build until
# representative device import/render coverage is available for narrower rules.
-keep class org.mozilla.universalchardet.** { *; }
-keep class org.jsoup.** { *; }
-keep class com.caverock.androidsvg.** { *; }

# jsoup's package annotation is compile-time nullness metadata, not runtime code.
-dontwarn org.jspecify.annotations.NullMarked
