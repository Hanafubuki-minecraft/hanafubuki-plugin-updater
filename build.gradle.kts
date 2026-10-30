plugins {
    `java-library`
    `maven-publish`
}

group = "com.hanafubuki"
version = "1.0.0"

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
    implementation("com.google.code.gson:gson:2.10.1")
    
    // SnakeYAML for config parsing
    implementation("org.yaml:snakeyaml:2.7")
    
    // JUnit for testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.9.2")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.test {
    useJUnitPlatform()
}