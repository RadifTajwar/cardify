# Credential Manager finds its Google Play services provider by reflection, so release shrinking must keep it.
-if class androidx.credentials.CredentialManager
-keep class androidx.credentials.playservices.** { *; }
