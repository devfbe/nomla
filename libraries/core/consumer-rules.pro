# JNI_OnLoad binds the native methods by class and method name (RegisterNatives) and fails the
# whole library if one is missing, so these must keep their names and survive even when unused.
-keep class koeln.becker.nomla.mumble.audio.native.*Native { native <methods>; }
