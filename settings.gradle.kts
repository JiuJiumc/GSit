rootProject.name = "GSit"

include(":core")

// The supplied customization targets modern 26.2/26.3 servers; retain all upstream sources.
val requestedVersions = providers.gradleProperty("jiujiuVersions").orElse("v26_2,v26_3")
    .get().split(",").map { it.trim() }.toSet()

listOf(
    "v1_17_1",
    "v1_18", "v1_18_2",
    "v1_19", "v1_19_1", "v1_19_3", "v1_19_4",
    "v1_20", "v1_20_2", "v1_20_3", "v1_20_5",
    "v1_21", "v1_21_2", "v1_21_4", "v1_21_5", "v1_21_6", "v1_21_9", "v1_21_11",
    "v26_1", "v26_2", "v26_3"
).also { available ->
    require(requestedVersions.isNotEmpty() && requestedVersions.all { it in available }) {
        "jiujiuVersions contains an unknown GSit adapter: $requestedVersions"
    }
}.filter { it in requestedVersions }.forEach {
    include(":$it")
    project(":$it").projectDir = file("mcv/$it")
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
