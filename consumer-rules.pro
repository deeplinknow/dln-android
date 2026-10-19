# DeepLinkNow Android SDK consumer rules (AND-04).
#
# The SDK decodes server JSON with hand-written decoders, but its public model classes are
# Gson-annotated and apps (and the SDK's own on-disk cache encoder) may serialize them with
# Gson. Keep the models, their fields and @SerializedName names so R8 full mode in host apps
# cannot rename or strip them.
-keep class com.deeplinknow.DeeplinkMatch { *; }
-keep class com.deeplinknow.Match { *; }
-keep class com.deeplinknow.MatchResponse { *; }
-keep class com.deeplinknow.MatchDetails { *; }
-keep class com.deeplinknow.MatchComponentDetails { *; }
-keep class com.deeplinknow.DeviceMatchDetails { *; }
-keep class com.deeplinknow.DeviceMatchDetails$* { *; }
-keep class com.deeplinknow.LocaleMatchDetails { *; }
-keep class com.deeplinknow.LocaleMatchDetails$* { *; }
-keep class com.deeplinknow.TimeProximityDetails { *; }
-keep class com.deeplinknow.InitResponse { *; }
-keep class com.deeplinknow.InitResponse$* { *; }
-keep class com.deeplinknow.ReferrerLookupResponse { *; }
-keep class com.deeplinknow.ReferrerData { *; }
-keep class com.deeplinknow.Fingerprint { *; }
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepattributes Signature, *Annotation*
