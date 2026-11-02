# IME 服务由系统通过 BIND_INPUT_METHOD 绑定，必须保留
-keep class com.tvboard.ime.ime.TvImeService { *; }

# NanoHTTPD 反射调用极少，但保留其实现类避免裁剪异常
-keep class fi.iki.elonen.** { *; }
-dontwarn fi.iki.elonen.**

# ZXing core 纯 Java，无需额外规则；避免其可选依赖告警
-dontwarn java.awt.**
-dontwarn javax.imageio.**

# Kotlin 元数据
-keepattributes *Annotation*, InnerClasses
-dontnote kotlin.**
