# PDFBox (объединение PDF, сборка PDF с текстом)
-keep class com.tom_roush.pdfbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**

# Tesseract (JNI)
-keep class com.googlecode.tesseract.android.** { *; }
