// Scoped PIT (pitest) mutation-testing config for StellarAddress only.
//
// Not applied by default from app/build.gradle.kts — mutation testing is
// slow and this is meant to be run deliberately (locally or as a manual CI
// job), not on every build. To use it, apply this file from
// app/build.gradle.kts:
//
//   apply(from = "mutation-testing.gradle.kts")
//
// and add the plugin + dependency to the version catalog:
//
//   [plugins]
//   pitest = { id = "info.solidsoft.pitest", version = "1.15.0" }
//
//   [dependencies]
//   pitest-kotlin = "org.pitest:pitest-kotlin-plugin:1.1.4"
//
// See shared/MUTATION_TESTING.md for rationale and the current baseline.

configure<info.solidsoft.gradle.pitest.PitestPluginExtension> {
    targetClasses.set(listOf("com.ethosprotocol.models.StellarAddress"))
    targetTests.set(listOf("com.ethosprotocol.StellarAddressTest"))
    outputFormats.set(listOf("HTML", "XML"))
    mutationThreshold.set(90)
    junit5PluginVersion.set("1.2.1")
    verbose.set(false)
}
