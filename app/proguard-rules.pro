# JNA and the generated UniFFI bindings resolve types reflectively, so R8 must
# not rename or strip them -- doing so breaks the native call at runtime with
# an UnsatisfiedLinkError that is very hard to trace back here.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class uniffi.filemanager_core.** { *; }
-dontwarn java.awt.**

# --- Network storage and the FTP server -------------------------------------
#
# All four protocol libraries and the server pick implementations by name at
# runtime: sshj looks up ciphers, key-exchange methods and signature schemes
# through a factory list, smbj resolves SMB2 packet types by their wire code,
# and Apache FtpServer builds its command set from class names. R8 sees nothing
# calling those classes and removes them, and the failure lands at runtime as a
# handshake that cannot agree on an algorithm -- a long way from the cause.
-keep class net.schmizz.sshj.** { *; }
-keep class com.hierynomus.** { *; }
-keep class org.apache.ftpserver.** { *; }
-keep class org.apache.mina.** { *; }
-keep class org.apache.commons.net.** { *; }
-keep class net.i2p.crypto.eddsa.** { *; }

# BouncyCastle registers its algorithms through a provider that is looked up by
# name, so the same applies. Android ships its own stripped copy under
# com.android.org.bouncycastle, which is why this one can keep its package.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# These libraries are built for a desktop JVM and reference classes Android
# does not have. Nothing here calls the code paths that would need them, but R8
# still checks every reference it can see.
-dontwarn javax.naming.**
-dontwarn java.lang.management.**
-dontwarn org.slf4j.impl.**
-dontwarn org.ietf.jgss.**
-dontwarn net.schmizz.sshj.signature.**
-dontwarn org.apache.mina.**

# smbj dispatches its internal events through mbassador, which finds handlers
# by reflecting over annotated methods. The artifact is net.engio:mbassador but
# the package inside it is net.engio.mbassy -- a rule naming the artifact
# matches nothing and silently protects none of it.
-keep class net.engio.mbassy.** { *; }
-keepclassmembers class * {
    @net.engio.mbassy.listener.Handler *;
}

# mbassador can filter messages with Java EL expressions. Android has no EL
# implementation, and nothing here uses filtered handlers, but the classes that
# would call it are still reachable. Without this R8 stops with "Missing class
# javax.el.BeanELResolver" and no APK is produced.
-dontwarn javax.el.**

# SLF4J finds its provider through ServiceLoader. Without a provider on the
# classpath it falls back to doing nothing, which is what we want -- but the
# lookup itself must survive or it throws on the way to that conclusion.
-keep class org.slf4j.** { *; }

# Apache FtpServer can be configured from a Spring context, and its POM marks
# Spring optional, so those classes are referenced but never packaged. R8 treats
# a missing class as an error, so this says plainly that it is expected -- the
# app configures the server in code and never touches that path.
-dontwarn org.springframework.**
-dontwarn org.apache.commons.logging.**

# Likewise for the optional pieces the network libraries reach for on a desktop
# JVM: JCE providers we do not ship, and a JMX bean MINA registers when it can.
-dontwarn javax.management.**

# sshj can authenticate with Kerberos through JAAS. LoginContext is in the JDK
# but not on Android, and this app only ever authenticates with a password.
-dontwarn javax.security.auth.login.**
-dontwarn javax.security.sasl.**
-dontwarn org.bouncycastle.jce.provider.**
-dontwarn net.i2p.crypto.eddsa.**
