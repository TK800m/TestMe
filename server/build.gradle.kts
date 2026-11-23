plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

application {
    mainClass.set("nl.tapo.deurbel.server.MainKt")
}

dependencies {
    implementation(project(":core"))
    implementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}

/** Single runnable jar for the Raspberry Pi (java -jar deurbel-server.jar). */
tasks.register<Jar>("fatJar") {
    archiveFileName.set("deurbel-server.jar")
    manifest { attributes["Main-Class"] = "nl.tapo.deurbel.server.MainKt" }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) } }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.EC", "META-INF/versions/**", "module-info.class")
    }
}
