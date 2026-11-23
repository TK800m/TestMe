plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    // org.json is part of Android; only needed on the plain JVM for compiling/testing
    compileOnly("org.json:json:20240303")
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")
    testImplementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}
