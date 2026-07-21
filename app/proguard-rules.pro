# 默认规则，暂无需自定义

# ✅ 保留 BouncyCastle：它内部有一些按 Provider 机制反射加载的类，
# release 包开了 minify，不加这条规则可能被误删导致 Argon2 在 release 包里崩溃
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**


