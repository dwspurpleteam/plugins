import com.google.protobuf.gradle.id
import org.springframework.boot.gradle.plugin.SpringBootPlugin

// Код плагина. Это обычная библиотека: приложение из неё собирает assembly.
plugins {
    `java-library`
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.protobuf)
}

repositories {
    mavenCentral()
}

// Плагин Spring Boot подключён только в assembly, а версии зависимостей берутся из того же BOM.
dependencyManagement {
    imports {
        mavenBom(SpringBootPlugin.BOM_COORDINATES)
    }
}

dependencies {
    implementation(libs.spring.boot.starter.webmvc)

    // Клиент streamapi: события звонка по gRPC
    implementation(libs.grpc.netty.shaded)
    implementation(libs.grpc.protobuf)
    implementation(libs.grpc.stub)
    implementation(libs.protobuf.java)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.grpc.inprocess)
    testRuntimeOnly(libs.junit.platform.launcher)
}

java {
    toolchain {
        // Java 25 LTS: на ней компилируются и запускаются тесты, какой бы JDK ни запускал Gradle.
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // Spring берёт из байткода имена параметров, например для @PathVariable. В assembly флаг добавляет плагин Spring Boot.
    options.compilerArgs.add("-parameters")
}

protobuf {
    // Без плагина Spring Boot protoc и protoc-gen-grpc-java настраиваются вручную.
    // Их версии совпадают с версиями protobuf и gRPC из BOM.
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
                id("grpc") {
                    option("@generated=omit")
                }
            }
        }
    }
}

tasks.test {
    useJUnitPlatform()
}
