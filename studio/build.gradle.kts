plugins {
    id("formatter-conventions")
    id("kotlin-conventions")
    id("test-conventions")
}

dependencies {
    implementation(libs.jackson.databind)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.kotlin.coroutines.core)
    implementation(projects.server.services)
}
