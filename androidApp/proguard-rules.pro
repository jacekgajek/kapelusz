# Keep rules beyond what the libraries ship (kotlinx.serialization, Ktor, Credential Manager bring their own).

# Google's code scanner (Join band's Scan QR code, I6 §10): ML Kit finds its parts by reflection, and the
# release build crashed creating the scanner once R8 had renamed or removed them (1.13.0).
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_code_scanner.** { *; }
