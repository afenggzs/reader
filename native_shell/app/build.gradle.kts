import java.util.Properties

plugins {
    id("com.android.application")
}

// 版本号由 Android/tool/stamp_version.py 写进 version.properties：
//   versionName=1.1.2-20261107204530
//   versionCode=29626725        （距 1970 的分钟数，单调递增）
// 每次构建都刷新，保证「改完代码能覆盖安装」，也方便在「我的」页面对上号。
val stamp: Properties = Properties().apply {
    val f = rootProject.file("version.properties")
    if (f.exists()) {
        f.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.novelreader.webshell"
    compileSdk = 36

    defaultConfig {
        // 包名固定为 com.novelreader.webshell。改名会让用户丢掉整个书库
        // （正文存在 WebView 的 IndexedDB 里，跟包名/源绑定），发版后别再动。
        applicationId = "com.novelreader.webshell"
        minSdk = 24
        targetSdk = 36
        versionCode = (stamp.getProperty("versionCode") ?: "1").toInt()
        versionName = stamp.getProperty("versionName") ?: "1.1.2"
    }

    buildTypes {
        release {
            // 打开 R8：这个工程 dex 本来就小，收益主要是去掉未使用的 androidx 类
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // 用调试签名：构建完直接 adb install 就能覆盖升级，不用先配 keystore。
            // 正式发包时换成 release keystore，密钥文件别提交（.gitignore 已排除）。
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // assets 里的 index.html 已经是压缩过的单文件，再压一次收益很小，
    // 但关掉可以让 WebView 直接 mmap 读取，冷启动快一点
    androidResources {
        noCompress += "html"
    }

    // 不再从包里剔 META-INF / kotlin 元数据：那是纯 Java 工程之前顺手加的优化，
    // 省下不到 20KB，却有可能连带删掉 ServiceLoader 需要的东西。稳妥为先。
}

dependencies {
    // 只为 WebViewAssetLoader：把 assets 映射到一个 https 源，
    // 这样 IndexedDB / localStorage 有正常 origin。
    // 用 file:// 的话 WebView 会禁掉 IndexedDB，书的正文就存不住。
    implementation("androidx.webkit:webkit:1.15.0")
}
