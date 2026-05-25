plugins {
    id("groovy")
    id("maven-publish")
}

group = "ru.kazantsev.nsmp.modules"
version = "2.3.0"

tasks.javadoc {
    options.encoding = "UTF-8"
}

sourceSets["main"].groovy.srcDir("src/main/placeholders")

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    sourceCompatibility = JavaVersion.VERSION_21
    withJavadocJar()
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = project.group.toString()
            artifactId = project.name
            version = project.version.toString()
        }
    }
    repositories {
        mavenLocal()
    }
}

repositories {
    maven {
        url = uri("https://maven.pkg.github.com/exeki/*")
        credentials {
            username = System.getenv("GITHUB_USERNAME")
            password = System.getenv("GITHUB_TOKEN")
        }
    }
    mavenCentral()
    mavenLocal()
}

dependencies {
    implementation("org.apache.groovy:groovy:4.0.14")
    implementation("ru.kazantsev.nsd.sdk:global_variables:1.6.0")
    implementation("ru.kazantsev.nsmp.modules:web_api_components:2.3.3")
}

