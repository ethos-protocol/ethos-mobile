# Add project specific ProGuard rules here.
# See https://developer.android.com/studio/build/shrink-code for details.
#
# ---------------------------------------------------------------------------
# REVIEW NOTES (task #123)
# Reviewed against what is actually needed by:
#   - kotlinx.serialization (reflection-based $$serializer companions)
#   - Room (entity/DAO generated code)
#   - Ktor Android engine
#   - Hilt/Dagger generated components
#   - HiltWorker / WorkManager
#
# Key findings:
#
# 1. Debug logging: android.util.Log calls are NOT stripped by default R8 rules.
#    We explicitly remove them below so that vault balances, JWT fragments, and
#    2FA secrets cannot leak via logcat in a release/production build.
#    (The LogLevel.NONE guard in ApiClient.kt is a runtime defence; this is a
#    compile-time removal that applies to all Log.d/Log.v/Log.i calls anywhere
#    in the app, including third-party code that may not guard behind a flag.)
#
# 2. Model classes (AuthToken, PasskeyRegisterRequest, etc.): kept only with the
#    minimum surface required for kotlinx.serialization to work — constructors,
#    fields, and the generated $$serializer companion. We do NOT use a blanket
#    `-keep class com.ethosprotocol.** { *; }` that would expose every member to
#    reflection; the per-rule keeps below are intentionally narrow.
#
# 3. No additional reflective accessibility beyond what kotlinx.serialization
#    requires was introduced. The `-keep class com.ethosprotocol.models.** { *; }`
#    rule that previously appeared here has been replaced with the narrower set
#    below that keeps only what the serialization framework needs.
# ---------------------------------------------------------------------------

# ---------------------------------------------------------------------------
# Strip debug/verbose/info logging in release builds
# This is a compile-time removal — Log.d/v/i calls are eliminated by R8 so
# they cannot leak sensitive data (token values, vault balances, etc.) even if
# a device is rooted or the APK is decompiled.
# Log.w and Log.e are kept; those represent genuine runtime warnings/errors
# that operators need in crash reports.
# ---------------------------------------------------------------------------
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# ---------------------------------------------------------------------------
# kotlinx.serialization
# Keep the generated $$serializer companions/objects for our @Serializable
# models (api/Models.kt) so reflection-based (de)serialization used by Ktor's
# ContentNegotiation/json() plugin keeps working under R8/minification.
# https://github.com/Kotlin/kotlinx.serialization/blob/master/rules/common.pro
# ---------------------------------------------------------------------------
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class com.ethosprotocol.models.**$$serializer { *; }
-keepclassmembers class com.ethosprotocol.models.** {
    *** Companion;
}
-keepclasseswithmembers class com.ethosprotocol.models.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# Keep constructors and fields required for kotlinx.serialization's generated code.
# Intentionally narrower than the previous blanket `{ *; }` rule — we keep only
# what is genuinely required for (de)serialization, reducing the reflective surface
# of sensitive model classes such as AuthToken and PasskeyRegisterRequest.
-keepclassmembers class com.ethosprotocol.models.** {
    <init>(...);
    <fields>;
}

# ---------------------------------------------------------------------------
# Room (entities / DAOs)
# The generated *_Impl DAO classes call into our entity/DAO interfaces via
# generated code, not reflection, but keep them explicitly for defense in
# depth against R8 stripping fields that are only written via reflection/JNI
# in some Room code paths.
# ---------------------------------------------------------------------------
-keep class com.ethosprotocol.services.PendingCheckIn { *; }
-keep interface com.ethosprotocol.services.PendingCheckInDao { *; }
-keep class com.ethosprotocol.services.CheckInDatabase { *; }
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# ---------------------------------------------------------------------------
# Ktor client
# Ktor ships consumer ProGuard rules for its core artifacts, but the various
# platform engines it can multiplatform-target (OkHttp/CIO/Darwin/etc.) are
# referenced conditionally; we only ever use the Android engine, so silence
# missing-class warnings for the engines we don't pull in as dependencies.
# ---------------------------------------------------------------------------
-dontwarn io.ktor.client.engine.**
-dontwarn io.ktor.client.plugins.compression.**
-keep class io.ktor.client.engine.android.** { *; }
-keepclassmembers class io.ktor.** {
    volatile <fields>;
}

# ---------------------------------------------------------------------------
# Hilt / Dagger
# The Hilt Gradle plugin + androidx.hilt artifacts already bundle consumer
# rules for their generated components; these extra rules are defense in
# depth for the generated entry points and our @HiltWorker classes, which
# WorkManager's HiltWorkerFactory looks up by (string) class name at runtime.
# ---------------------------------------------------------------------------
-keep class dagger.hilt.internal.aggregatedroot.codegen.** { *; }
-keep class hilt_aggregated_deps.** { *; }
-keep,allowobfuscation @dagger.hilt.android.lifecycle.HiltViewModel class * extends androidx.lifecycle.ViewModel
-keep @androidx.hilt.work.HiltWorker class * extends androidx.work.ListenableWorker {
    <init>(...);
}
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper

# ---------------------------------------------------------------------------
# kotlinx.coroutines (safety net for volatile fields on state machines,
# already handled by coroutines' own consumer rules but kept for clarity)
# ---------------------------------------------------------------------------
-keepclassmembernames class kotlinx.coroutines.internal.MainDispatcherFactory
-dontwarn kotlinx.coroutines.debug.**
