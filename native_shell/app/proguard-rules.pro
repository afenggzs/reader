# 这个壳子只有 MainActivity 一个类，全部保留最省心（也就几 KB）。
-keep class com.novelreader.webshell.** { *; }

# WebViewAssetLoader 会按类名反射构造 PathHandler，别混淆 androidx.webkit。
-keep class androidx.webkit.** { *; }
-dontwarn androidx.webkit.**

# 清单里声明了 appComponentFactory，R8 看不出来它被系统反射使用。
-keep class androidx.core.app.CoreComponentFactory { *; }
