rootProject.name = "brewslot"

include(
    "libs:common",
    "libs:web",
    "libs:messaging",
    "libs:test-support",
    "services:order-service",
    "services:payment-service",
    "services:loyalty-service",
    "services:settlement-service",
    "services:notification-service",
    "e2e",
)

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
