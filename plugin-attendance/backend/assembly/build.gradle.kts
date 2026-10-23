import com.github.gradle.node.npm.task.NpmTask

// Приложение: main class, настройки, фронтенд и исполняемый jar.
plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.node)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":core"))
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.actuator)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.wiremock.standalone)
    testRuntimeOnly(libs.junit.platform.launcher)
}

java {
    toolchain {
        // Java 25 LTS: на ней компилируются и запускаются тесты и bootRun, какой бы JDK ни запускал Gradle.
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

// Фронтенд — отдельный npm-проект рядом с backend; его сборка попадает в jar.
val frontendDir = layout.settingsDirectory.dir("../frontend")

node {
    // Gradle сам скачивает Node.js: для сборки нужен только JDK.
    download = true
    version = libs.versions.nodejs.get()
    // Node.js лежит в backend/.gradle/, а не в папке модуля.
    workDir = layout.settingsDirectory.dir(".gradle/nodejs")
    nodeProjectDir = frontendDir
    npmInstallCommand = "ci"
}

val frontendBuild = tasks.register<NpmTask>("frontendBuild") {
    group = "build"
    description = "Собирает React-фронтенд в ../frontend/dist."
    dependsOn(tasks.npmInstall)
    npmCommand = listOf("run", "build")
    inputs.dir(frontendDir.dir("src"))
    inputs.dir(frontendDir.dir("public"))
    inputs.files(
        frontendDir.file("index.html"),
        frontendDir.file("package.json"),
        frontendDir.file("package-lock.json"),
        frontendDir.file("tsconfig.json"),
        frontendDir.file("vite.config.ts"),
    )
    outputs.dir(frontendDir.dir("dist"))
}

tasks.processResources {
    // Фронтенд отдаёт сам Spring Boot: страница и API живут в одном процессе.
    from(frontendBuild) { into("static") }
}

tasks.clean {
    delete(frontendDir.dir("dist"))
}

springBoot {
    buildInfo {
        excludes = setOf("time")
        // По умолчанию здесь было бы имя модуля, assembly.
        properties {
            artifact = "plugin-attendance"
            name = "plugin-attendance"
        }
    }
}

// gRPC загружает нативную TLS-библиотеку netty; без разрешения JDK 24+ пишет предупреждение.
tasks.bootJar {
    archiveFileName = "plugin-attendance.jar"
    manifest {
        attributes("Enable-Native-Access" to "ALL-UNNAMED")
    }
}

tasks.bootRun {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.jar {
    enabled = false
}

// Mock Result Gateway: WireMock с маппингами из ../wiremock. Отдельно от приложения, как настоящий Result Gateway.
val wiremockDir = layout.settingsDirectory.dir("../wiremock")
val wiremock = configurations.create("wiremock")

dependencies {
    wiremock(libs.wiremock.standalone)
}

tasks.register<JavaExec>("resultGatewayMock") {
    group = "application"
    description = "Запускает mock Result Gateway (WireMock) на порту 8089."
    classpath = wiremock
    mainClass = "wiremock.Run"
    args("--port", "8089", "--root-dir", wiremockDir.asFile.path, "--verbose", "--disable-banner")
}

tasks.test {
    useJUnitPlatform()
    // Тест приложения поднимает WireMock с теми же маппингами, что и resultGatewayMock.
    inputs.dir(wiremockDir)
    systemProperty("wiremock.root", wiremockDir.asFile.path)
}
