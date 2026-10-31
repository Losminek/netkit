# NetKit release (R8). Libraries with consumer rules (OkHttp, kotlinx.serialization, AndroidX,
# Compose, DataStore) bring their own; this file covers only what they cannot know.

# JSch creates ciphers, KEX, signatures and key-pair generators by class name from its config
# (JSch.getConfig -> Class.forName), including the names NetKit sets in SshKeys.kt
# (com.jcraft.jsch.bc.*). Renaming or removing any of them breaks SSH only at connect time.
-keep class com.jcraft.jsch.** { *; }

# BouncyCastle registers its algorithms in the JCA provider by class-name strings
# (BouncyCastleProvider -> addAlgorithm("...", "org.bouncycastle....$Spi")); JSch's bc package uses
# them for Ed25519/Ed448/X25519. Keep the whole provider rather than guessing the used subset.
-keep class org.bouncycastle.** { *; }

# Optional JSch integrations that do not exist on Android and are never configured by NetKit:
# Pageant / Windows agent (JNA), Unix-socket agent (junixsocket), Kerberos GSS-API, log4j and
# SLF4J loggers. Debug builds miss the same classes at runtime; R8 only has to stop failing on them.
# (List from build/outputs/mapping/release/missing_rules.txt, AGP 8.9.2, JSch 2.28.0.)
-dontwarn com.sun.jna.**
-dontwarn org.newsclub.net.unix.**
-dontwarn org.ietf.jgss.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.slf4j.**

# Stack traces in crash reports from a release build stay readable without shipping names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
