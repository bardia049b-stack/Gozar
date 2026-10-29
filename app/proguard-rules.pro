-keep class org.amnezia.awg.** { *; }
-keep class com.google.zxing.** { *; }
-dontwarn org.amnezia.awg.**

-keep class com.gozar.** { *; }

-keep public class * extends androidx.fragment.app.Fragment
-keep public class * extends androidx.recyclerview.widget.RecyclerView

-keepattributes *Annotation*
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
