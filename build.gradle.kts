plugins {
    java
    application
    id("org.openjfx.javafxplugin") version "0.1.0"
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
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("-Duser.language=en", "-Duser.country=US")
}

javafx {
    version = "21"
    modules = listOf("javafx.controls", "javafx.fxml")
}

// The mainClass belongs in this block.
application {
    mainClass.set("org.evochora.Main")
}