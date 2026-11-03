# Called from native code by name (native/remux.c).
-keep class dev.konan.mov2mp4.engine.Remux { native <methods>; }
-keep interface dev.konan.mov2mp4.engine.Remux$Progress { *; }
-keepclassmembers class * implements dev.konan.mov2mp4.engine.Remux$Progress {
    public boolean onProgress(int, long, long);
}
