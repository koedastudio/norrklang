# kotlinx.serialization: only core-subsonic's DTOs are @Serializable, so the
# keep rules are scoped to that package.
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class studio.koeda.norrklang.subsonic.model.**$$serializer { *; }
-keepclassmembers class studio.koeda.norrklang.subsonic.model.** { *** Companion; }
-keepclasseswithmembers class studio.koeda.norrklang.subsonic.model.** { kotlinx.serialization.KSerializer serializer(...); }
