# Consuming UFO Core

UFO Core is a required runtime mod and a compile dependency. During local
development, sibling projects can use a Gradle composite build so every Core
change is compiled immediately.

## Development setup

Add this to the consumer's `settings.gradle`:

```groovy
includeBuild('../RaishxCore') {
    dependencySubstitution {
        substitute module('com.raishxn.ufocore:raishxcore') using project(':')
    }
}
```

Add the version and dependency in the consumer:

```properties
raishxcore_version=0.1.0-alpha.4
raishxcore_version_range=[0.1.0-alpha.4,0.2)
```

```groovy
dependencies {
    implementation "com.raishxn.ufocore:raishxcore:${raishxcore_version}"
}
```

The consumer's `neoforge.mods.toml` must also declare `raishxcore` as a required
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

### Declaring missing weights

The Core never derives material value: nothing in the network says what an item is
worth, and the planner must not guess about content. A consumer that does know
declares exact integer weights per serialized key:

```java
import com.raishxn.ufocore.api.crafting.planner.MissingWeights;

MissingWeights.register("ufo:tier3_plate", 20L);
MissingWeights.unregister("ufo:tier3_plate"); // back to the default weight of one
```

`register` replaces any previous declaration, and a weight of one removes it because
one is the default and is never stored. A key nobody registered weighs one, so an
installation that declares nothing takes exactly the path it took before weights
existed. The operator scales the declared weights from
`config/raishxcore/core.toml`: `planner.missingWeights.multiplier` for all of them,
and `planner.missingWeights.overrides` for one key, so a pack can disagree without
recompiling the addon. Weights are read on the planner worker, so registering while
a plan is in flight cannot rewrite that plan.

Until Core 1.0, adapters outside `api` may receive breaking improvements.

## Release setup

Run `./gradlew build` to create the normal and sources jars. Run
`./gradlew publishMavenJavaPublicationToLocalUfoCoreRepository` to create a
file-based Maven repository under `build/repo` for release testing. A published
consumer build must resolve the same Core version declared in its mod metadata.
