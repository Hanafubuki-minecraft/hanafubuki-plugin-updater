plugins {
    `java-library`
    `maven-publish`
}

group = "com.hanafubuki"
version = providers.gradleProperty("version").get()

repositories {
    mavenCentral()
    maven {
        name = "PaperMC"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
    maven {
        name = "Sonatype"
        url = uri("https://oss.sonatype.org/content/repositories/snapshots/")
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.9-R0.1-SNAPSHOT")
    
    // Gson for JSON parsing
    implementation("com.google.code.gson:gson:2.15.0")
    
    // SnakeYAML for config parsing
    implementation("org.yaml:snakeyaml:2.7")
    
    // JUnit for testing - use BOM for aligned versions
    testImplementation(platform("org.junit:junit-bom:5.10.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.junit.jupiter:junit-jupiter-params")
    
    // Mockito
    testImplementation("org.mockito:mockito-core:5.12.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.12.0")
    
    // Paper API for tests (needed for YamlConfiguration, etc.)
    testImplementation("io.papermc.paper:paper-api:1.21.9-R0.1-SNAPSHOT")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.processResources {
    expand(project.properties)
}

tasks.test {
    useJUnitPlatform()
}