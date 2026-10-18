# Changelog

## 1.0.0

First release.

Codinject downloads Maven dependencies at runtime and puts them in a class loader, from Java 8 to Java 25.

- Resolves transitive dependencies from POMs: parents, properties, `dependencyManagement`, BOM imports, exclusions, and nearest wins like Maven.
- Reads Gradle module metadata (`.module`) when the POM says it's there, so Kotlin Multiplatform libraries get their `-jvm` jar and Guava gets the `jre` flavor.
- Checks every download against its `.sha1` and caches it in `libs/` with the `~/.m2` layout.
- Relocates packages with jar-relocator, downloaded on first use.
- Loads into an isolated class loader, or into yours with `parent(true)` / `into(loader)`. On Java 16+ that needs the agent, `--add-opens` or the Unsafe fallback, which prints a warning on Java 24+.

Tested on Java 8, 11, 17, 21 and 25.

```groovy
implementation 'com.github.ricodevvv:Codinject:1.0.0'
```
