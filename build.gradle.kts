plugins {
    java
}

group = "com.hanafubuki"
version = providers.gradleProperty("version").get()

repositories {
    mavenCentral()
    maven {
        name = "PaperMC"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
}

dependencies {
    // Paper API. Paper also ships Gson, SnakeYAML and Adventure (MiniMessage) at runtime,
    // so the plugin jar needs no bundled libraries.
    compileOnly("io.papermc.paper:paper-api:1.21.9-R0.1-SNAPSHOT")

    // The core (update engine, sources, utilities) only uses Gson and SnakeYAML.
    // Versions match what Paper 1.21.9 provides at runtime.
    compileOnly("com.google.code.gson:gson:2.11.0")
    compileOnly("org.yaml:snakeyaml:2.2")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("com.google.code.gson:gson:2.11.0")
    testImplementation("org.yaml:snakeyaml:2.2")
    testImplementation("io.papermc.paper:paper-api:1.21.9-R0.1-SNAPSHOT")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
    options.compilerArgs.addAll(listOf("-Xlint:unchecked", "-Xlint:rawtypes"))
}

tasks.processResources {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    // Only plugin.yml is a template. config.yml is copied as-is.
    filesMatching("plugin.yml") {
        expand(props)
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
