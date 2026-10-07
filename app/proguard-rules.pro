# Navigation 3 routes are @Serializable keys; keep their serializers for back-stack restoration.
-keepclassmembers @kotlinx.serialization.Serializable class com.spendwise.app.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# Strip verbose/debug/info logging from release builds (never log financial data — ADR-002).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
