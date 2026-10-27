// Root build file. Same toolchain as Lumen (proven on GitHub Actions in October 2026).
// AGP 9 compiles Kotlin itself, so there is no org.jetbrains.kotlin.android plugin.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
