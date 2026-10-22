plugins {
    // Подключаются в модулях; здесь объявлены, чтобы вся сборка загружала их один раз.
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
    alias(libs.plugins.protobuf) apply false
    alias(libs.plugins.node) apply false
}
