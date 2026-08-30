plugins {
    id("java-library")
    id("maven-publish")
}

group = "su.nightexpress.excellenteconomy"
version = "2.8.0-tax-v1"

java {
    toolchain {
         languageVersion = JavaLanguageVersion.of(25)
    }
    withSourcesJar()
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.nightexpressdev.com/releases")
    maven("https://jitpack.io")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    maven("https://repo.rosewooddev.io/repository/public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.+")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7") {
        exclude(group = "org.bukkit", module = "bukkit")
    }
    compileOnly("su.nightexpress.nightcore:main:2.16.1")
    compileOnly("me.clip:placeholderapi:2.11.6")
    compileOnly("org.black_ixx:playerpoints:3.0.0")

    // Only needed so src/test can compile the placeholder contract sample.
    testImplementation("io.papermc.paper:paper-api:26.1.2.build.+")
    testImplementation("su.nightexpress.nightcore:main:2.16.1")
}

// Prints what the transfer-tax placeholders actually return, straight from samples/tax.yml.
// Run: gradlew printPlaceholderSamples
tasks.register<JavaExec>("printPlaceholderSamples") {
    group = "verification"
    description = "Prints live output of the transfer tax placeholders (cross-plugin contract)."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "su.nightexpress.excellenteconomy.tax.PlaceholderContractSample"
    args("samples/tax.yml")
}

tasks {
    withType<JavaCompile> {
        options.encoding = "UTF-8"
    }

    // Version already distinguishes this build from a stock 2.8.0 drop:
    // ExcellentEconomy-2.8.0-tax-v1.jar

    // src/test holds a runnable sample (see printPlaceholderSamples), not JUnit tests.
    test {
        failOnNoDiscoveredTests.set(false)
    }

    processResources {
        // Replicates maven <filtering>true</filtering> for plugin.yml
        // Replaces ${version} with the project version.
        filesMatching("*plugin.yml") {
            expand(mapOf("version" to project.version))
        }
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifact(tasks.named("jar"))
            artifact(tasks.named("sourcesJar")) {
                classifier = "sources"
            }
            artifactId = project.name
        }
    }
    repositories {
        maven {
                name = "nightexpress"
                url = uri("https://repo.nightexpressdev.com/releases")
                credentials {
                    username = System.getenv("REPOSILITE_USER")
                    password = System.getenv("REPOSILITE_PASSWORD")
                }
        }
    }
}