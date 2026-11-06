plugins {
    kotlin("jvm") version "2.4.21"
    id("com.apollographql.apollo") version "5.2.0"
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
}

apollo {
    service("service") {
        packageName.set("repro")
    }
}

dependencies {
    implementation("com.apollographql.apollo:apollo-runtime:5.2.0")
    implementation("com.apollographql.cache:normalized-cache:1.0.9")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

    testImplementation(kotlin("test-junit"))
    testImplementation("com.squareup.okhttp3:mockwebserver3:5.5.1")
}

tasks.test {
    useJUnit()
    testLogging {
        events("passed", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.SHORT
        showStandardStreams = true
    }
}
