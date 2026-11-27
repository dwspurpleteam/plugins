import com.github.gradle.node.npm.task.NpmTask
import com.google.protobuf.gradle.id

// Приложение: main class, настройки, фронтенд и исполняемый jar.
plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.node)
    alias(libs.plugins.protobuf)
}

repositories {
    mavenCentral()
    exclusiveContent {
        forRepository {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/dwspurpleteam/plugin-sdk-java")
                credentials {
                    username = providers.gradleProperty("gpr.user")
                        .orElse(providers.environmentVariable("GITHUB_USERNAME"))
                        .orElse(providers.environmentVariable("GITHUB_ACTOR"))
                        .orNull
                    password = providers.gradleProperty("gpr.key")
                        .orElse(providers.environmentVariable("GITHUB_TOKEN"))
                        .orElse(providers.environmentVariable("GH_TOKEN"))
                        .orNull
                }
            }
        }
        filter { includeModule("pluginsdk", "plugin-sdk") }
    }
}

dependencies {
    implementation(libs.plugin.sdk)
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.pdfbox)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.grpc.netty.shaded)
    testImplementation(libs.grpc.protobuf)
    testImplementation(libs.grpc.stub)
    testImplementation(libs.protobuf.java)
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
    options.compilerArgs.add("-parameters")
}

// Фронтенд — отдельный npm-проект рядом с backend; его сборка попадает в jar.
val frontendDir = layout.settingsDirectory.dir("../frontend")

node {
    // Gradle сам скачивает Node.js: для сборки нужен только JDK.
    download = true
    version = libs.versions.nodejs.get()
    // Node.js скачивается в backend/.gradle/.
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

val frontendTest = tasks.register<NpmTask>("frontendTest") {
    group = "verification"
    description = "Проверяет сетевые запросы и скачивание отчётов без дополнительных npm-зависимостей."
    dependsOn(tasks.npmInstall)
    npmCommand = listOf("test")
    inputs.dir(frontendDir.dir("src"))
    inputs.dir(frontendDir.dir("test"))
    inputs.files(frontendDir.file("package.json"), frontendDir.file("package-lock.json"))
}

tasks.check {
    dependsOn(frontendTest)
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

tasks.test {
    useJUnitPlatform()
}

protobuf {
    // Test gRPC fixtures use the protobuf and gRPC versions managed by Spring Boot.
    val managedVersions = dependencyManagement.managedVersions
    protoc {
        artifact = "com.google.protobuf:protoc:${managedVersions.getValue("com.google.protobuf:protobuf-java")}"
    }
    plugins {
        id("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:${managedVersions.getValue("io.grpc:protoc-gen-grpc-java")}"
        }
    }
    generateProtoTasks {
        all().configureEach {
            plugins {
                configureEach {
                    if (name == "grpc") {
                        option("@generated=omit")
                    }
                }
            }
        }
    }
}
