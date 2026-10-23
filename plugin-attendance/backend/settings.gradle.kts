plugins {
    // Скачивает JDK для toolchain, если подходящий не установлен.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "plugin-attendance"

// core — код плагина; assembly — main class, настройки и исполняемый jar.
include("core", "assembly")
