# youtubedl-android ships prebuilt native binaries invoked by reflection/JNI glue;
# keep its public API surface intact.
-keep class com.yausername.** { *; }
-dontwarn com.yausername.**

-keepattributes *Annotation*
-keepclassmembers class * {
    @androidx.room.* <methods>;
}

# youtubedl-android unzips its bundled Python/ffmpeg runtime with Apache Commons Compress.
# Its ZIP extra-field registry instantiates classes by reflection (ExtraFieldUtils.register);
# without this rule R8 makes them abstract/merged and first launch of a fresh install crashes
# with "class xx is not a concrete class".
-keep class org.apache.commons.compress.** { *; }
-dontwarn org.apache.commons.compress.**
