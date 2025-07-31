plugins {
    java
    application // DIESE ZEILE IST WICHTIG
    id("org.openjfx.javafxplugin") version "0.1.0"
}

group = "org.evochora"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

// JUnit-Abhängigkeiten für zukünftige Tests
dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

tasks.test {
    useJUnitPlatform()
}

javafx {
    version = "21"
    modules = listOf("javafx.controls")
}

application {
    mainClass.set("org.evochora.Main")
}