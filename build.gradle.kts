plugins {
    java
    application
    id("org.openjfx.javafxplugin") version "0.1.0"
    jacoco
}

group = "org.evochora"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:5.10.2")
    testImplementation("org.mockito:mockito-core:5.12.0")
    testImplementation("org.assertj:assertj-core:3.26.3")
}

tasks.test {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
    classDirectories.setFrom(
        files(classDirectories.files.map {
            fileTree(it) {
                exclude("org/evochora/ui/**", "org/evochora/Main*")
            }
        })
    )
}

javafx {
    version = "21"
    modules = listOf("javafx.controls", "javafx.fxml")
}

// Die mainClass gehört in diesen Block.
application {
    mainClass.set("org.evochora.Main")
}