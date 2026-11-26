plugins {
    `java-library`
    `maven-publish`
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:${providers.gradleProperty("jiujiuPaper26_3").get()}")
    // EngineHub's Maven endpoint returns HTTP 403; these provided APIs are compile-only.
    compileOnly(fileTree(rootProject.file("compile-libs")) { include("*.jar") })
    compileOnly("me.clip:placeholderapi:2.12.3")
    compileOnly("com.github.GriefPrevention:GriefPrevention:18.0.0")
    compileOnly("com.intellectualsites.plotsquared:plotsquared-core:7.6.0")
}

java {
    disableAutoTargetJvm()
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks.compileJava {
    options.release = 25
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = project.group.toString()
            artifactId = project.name
            version = project.version.toString()
            from(project.components["java"])
        }
    }
}
dependencies {
    compileOnly(files(rootProject.file("native-api/shiroha-api-26.2-SNAPSHOT.jar")))
    testImplementation("org.mockito:mockito-core:5.21.0")
    testImplementation("org.xerial:sqlite-jdbc:3.50.3.0")
}
configurations.testImplementation { extendsFrom(configurations.compileOnly.get()) }
val mockitoAgent = configurations.create("mockitoAgent")
dependencies { mockitoAgent("org.mockito:mockito-core:5.21.0") { isTransitive = false } }
val jiujiuRegression = tasks.register<JavaExec>("jiujiuRegression") {
    group = "verification"
    dependsOn(tasks.testClasses, rootProject.tasks.named("shadowJarDev"))
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("cn.jiujiu.gsit.carry.MergeRegressionTest")
    workingDir = rootProject.projectDir
    systemProperty("gsit.test.jar", rootProject.layout.buildDirectory.file("libs/GSit-${rootProject.version}.jar").get().asFile.absolutePath)
    jvmArgs("-javaagent:${mockitoAgent.singleFile.absolutePath}")
}
tasks.check { dependsOn(jiujiuRegression) }
// Maintenance regressions are executable probes, invoked by check above.
tasks.test { enabled = false }
