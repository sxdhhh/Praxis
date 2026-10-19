# Praxis 保留 Room / Compose 反射所需符号
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-keep class dev.praxis.data.db.** { *; }
-dontwarn org.jetbrains.annotations.**
