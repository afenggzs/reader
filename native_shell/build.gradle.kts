// AGP 9.1.0 / Gradle 9.3.1。
// 这两个版本的依赖本机已缓存，不用联网拉 —— 这台机器访问官方源只有 30 KB/s。
// 纯 Java 工程，不引 Kotlin 插件：少一层编译器、少一份 kotlin-stdlib，
// 也少一个「R8 把 kotlin 运行时裁坏」的可能。
plugins {
    id("com.android.application") version "9.1.0" apply false
}
