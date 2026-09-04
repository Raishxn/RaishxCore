# Consuming UFO Core

UFO Core is a required runtime mod and a compile dependency. During local
development, sibling projects can use a Gradle composite build so every Core
change is compiled immediately.

## Development setup

Add this to the consumer's `settings.gradle`:

```groovy
includeBuild('../UFO-Core-1.21.1') {
    dependencySubstitution {
        substitute module('com.raishxn.ufocore:ufocore') using project(':')
    }
}
```

Add the version and dependency in the consumer:

```properties
ufocore_version=0.1.0-alpha.1
ufocore_version_range=[0.1.0-alpha.1,0.2)
```

```groovy
dependencies {
    implementation "com.raishxn.ufocore:ufocore:${ufocore_version}"
}
```

The consumer's `neoforge.mods.toml` must also declare `ufocore` as a required
dependency on both client and server. UFO Future is the reference integration.

## API boundaries

- `api.amount`: exact nonnegative values, rational rates, bounded encoding and
  compact magnitude formatting.
- `api.tier`: data-oriented tier definitions without addon content.
- `api.multiblock`: immutable structure definitions, roles and scan/runtime
  result contracts.
- `api.port`: deterministic adapters for machine I/O.
- `api.transaction`: simulate/plan/commit allocation contracts.
- `api.network`: stable action identity used by packet guards.
- `client.gui.widget`: reusable AE2-style controls; client only.
- `neoforge.network`: NeoForge validation adapter; server only.

Until Core 1.0, adapters outside `api` may receive breaking improvements.

## Release setup

Run `./gradlew build` to create the normal and sources jars. Run
`./gradlew publishMavenJavaPublicationToLocalUfoCoreRepository` to create a
file-based Maven repository under `build/repo` for release testing. A published
consumer build must resolve the same Core version declared in its mod metadata.

