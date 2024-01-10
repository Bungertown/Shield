plugins {
    id("java")
    id("com.github.johnrengelman.shadow") version "8.1.1"
    id("xyz.jpenilla.run-paper") version "2.2.0"
}

group = "town.bunger"
version = "0.1.0"

repositories {
    mavenCentral()
    // Paper
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://oss.sonatype.org/content/groups/public/")
    // Cloud command framework
    maven("https://oss.sonatype.org/content/groups/public/")
}

dependencies {
    // Paper
    compileOnly("io.papermc.paper:paper-api:1.20-R0.1-SNAPSHOT")
    // Command framework
    implementation("cloud.commandframework:cloud-paper:1.8.4")
    implementation("cloud.commandframework:cloud-annotations:1.8.4")
    implementation("cloud.commandframework:cloud-minecraft-extras:1.8.4")
}


java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}

tasks {
    assemble {
        dependsOn(shadowJar)
    }
    processResources {
        val props = mapOf("version" to version)
        inputs.properties(props)
        filteringCharset = "UTF-8"
        filesMatching("paper-plugin.yml") {
            expand(props)
        }
    }
    runServer {
        minecraftVersion("1.20.4")
        downloadPlugins {
            url("https://download.luckperms.net/1529/bukkit/loader/LuckPerms-Bukkit-5.4.116.jar")
        }
    }
}