# PdfBox-Android loads fonts, filters and glyph lists by name at runtime; shrinking them breaks PDF text and images.
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**
-dontwarn org.apache.commons.logging.**
-dontwarn javax.xml.stream.**
