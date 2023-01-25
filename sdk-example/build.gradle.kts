
repositories {
    mavenCentral()
    maven {
        url = uri("https://oss.sonatype.org/content/repositories/snapshots")
    }
    maven { url = uri("https://plugins.gradle.org/m2/")}
    maven {
        url = uri("https://repo.maven.apache.org/maven2/")
    }
}

plugins {
    id("java")
}

version "1.0-SNAPSHOT"

dependencies {
    implementation("com.hedera.hashgraph:sdk:2.19.0")
    implementation("io.grpc:grpc-netty-shaded:1.46.0")
    implementation("io.github.cdimascio:dotenv-java:2.3.2")
    implementation("org.slf4j:slf4j-nop:2.0.3")
    implementation("com.google.code.gson:gson:2.8.8")
    implementation("com.github.spotbugs:spotbugs-annotations:4.7.3")

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.8.1")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.8.1")
}

tasks.test {
   useJUnitPlatform()
}
