# Codinject

Load Maven dependencies at runtime, from Java 8 to Java 25. Same idea as [ezlib](https://github.com/saicone/ezlib): your jar stays small, and the libraries get downloaded and put in a class loader when the app starts. Codinject also resolves transitive dependencies, so you don't have to list every single jar by hand.

```java
Codinject codinject = new Codinject(Paths.get("libs"));

codinject.dependency("redis.clients:jedis:5.1.0")
        .relocate("redis{}clients{}jedis", "my{}plugin{}libs{}jedis")
        .parent(true)
        .load();
```

That downloads Jedis and everything it needs (slf4j, commons-pool2, json, gson) from Maven Central, checks each file against its `.sha1`, relocates the packages and adds the jars to your class loader. Downloads are cached in `libs/`, so the next start doesn't touch the network.

There are zero dependencies. jar-relocator and ASM get downloaded the first time you relocate something, and they live in their own class loader.

## Install

It's on JitPack:

```groovy
plugins {
    id 'com.gradleup.shadow' version '9.3.1'
}

repositories {
    maven { url = uri('https://jitpack.io') }
}

dependencies {
    implementation 'com.github.ricodevvv:Codinject:1.0.0'
}

shadowJar {
    relocate 'com.ricodevvv.codinject', "${project.group}.libs.codinject"
}
```

```xml
<repository>
    <id>jitpack</id>
    <url>https://jitpack.io</url>
</repository>

<dependency>
    <groupId>com.github.ricodevvv</groupId>
    <artifactId>Codinject</artifactId>
    <version>1.0.0</version>
</dependency>
```

Shade it into your jar and relocate it so it doesn't clash with other plugins or apps that ship it too.

## Usage

Coordinates use the usual Gradle format: `group:artifact:version`, with an optional `:classifier` and `@extension`.

```java
Codinject codinject = new Codinject(Paths.get("libs"))
        .repository("https://jitpack.io/");

codinject.dependency("com.google.code.gson:gson:2.11.0").load();
codinject.dependency("io.netty:netty-transport-native-epoll:4.1.100.Final:linux-x86_64").load();
codinject.dependency(Paths.get("plugins/extra.jar")).load();
```

The version can also be `latest`, `release`, a `-SNAPSHOT` or a Maven range like `[2.10,3.0)`. These need `maven-metadata.xml` from the repository, so they always hit the network.

What you can chain before `load()`:

| Method | What it does |
| --- | --- |
| `repository(url)` | Extra repository just for this dependency, checked first |
| `relocate(from, to)` | Moves a package, applies to transitive jars too |
| `exclude("group:artifact")` | Leaves out transitive deps. `org.slf4j:*` and `io.netty:netty-*` work |
| `transitive(false)` | Loads only this jar |
| `parent(true)` | Loads into the target class loader instead of the isolated one |
| `into(loader)` | Loads into any class loader you give it |
| `unlessPresent("com.foo.Bar")` | Does nothing if that class is already loadable |

`load()` returns the jars it added. Errors come as `UncheckedIOException`, with what failed and where it looked.

`codinject.resolve(coords)` gives you the full tree without loading anything, and `codinject.download(coords)` downloads a single jar.

### Isolated or parent

By default jars go to `codinject.getClassLoader()`, an isolated class loader whose parent is the loader that loaded Codinject. Adding jars there is plain Java and works the same on every version. The catch is that your own classes can't see them directly. You load your entry class through that loader or use reflection.

With `parent(true)` the jars go straight into your class loader (your plugin loader, your app loader), so you can use them like any normal dependency. Change which loader that is with `codinject.target(loader)`.

### Relocation and shading

If you relocate your own jar with Shadow or Shade, they also rewrite plain strings like `"com.google.gson"` in your code, and then Codinject gets the already relocated name. To dodge that, write `{}` instead of dots: `"com{}google{}gson"`. Codinject turns them back into dots.

Relocated jars are saved in `libs/relocated/`, so relocation only happens once.

## Java 8 to 25

Adding a jar to a class loader you don't own used to be a one line reflection call. Since Java 16 the JDK blocks it, and since Java 24 the old `sun.misc.Unsafe` workaround prints a warning. Codinject tries these in order and uses the first one that works:

1. A public `addURL`, like the isolated loader has. Works everywhere.
2. `Instrumentation.appendToSystemClassLoaderSearch` for the system class loader, if the agent is loaded.
3. Reflection, when `java.base` is open to Codinject. Always on Java 8, and on newer versions with `--add-opens` or when the agent opened it.
4. The JDK internal lookup, taken through `sun.misc.Unsafe`. Works up to Java 25, but on 24+ the JVM prints this the first time:

```
WARNING: A terminally deprecated method in sun.misc.Unsafe has been called
```

It still works, it's just noisy. Any of these gets rid of it:

- **Runnable jar (Java 9+)**: add `Launcher-Agent-Class: your.relocated.package.CodinjectAgent` to your manifest. The JVM loads the agent itself when you run `java -jar`. This is the cleanest option.
- **`-javaagent:yourjar.jar`** with `Premain-Class` pointing to `CodinjectAgent`.
- **Your own agent**: call `CodinjectAgent.install(instrumentation)`.
- **JVM flags**: `--add-opens java.base/java.net=ALL-UNNAMED --add-opens java.base/jdk.internal.loader=ALL-UNNAMED`.
- **`--sun-misc-unsafe-memory-access=allow`**, if you just want to silence it.

If you'd rather get an error than touch Unsafe, use `codinject.allowUnsafe(false)`.

For a plugin running inside someone else's server you can't change the flags or the manifest. There the options are the isolated loader (no tricks at all) or living with the warning on 24+. Unsafe is planned to start failing in a future JDK. Until then it works up to Java 25.

## Transitive dependencies

Resolution reads the POMs, more or less like Maven:

- `compile` and `runtime` scopes. `test`, `provided`, `system` and `optional` deps are skipped.
- Parent POMs, properties (`${project.version}` and friends), `dependencyManagement` and BOM imports.
- Exclusions from the POMs plus the ones you pass with `exclude()`.
- On conflicts the closest declaration wins, like in Maven.
- `<repositories>` declared in POMs are used too, but only the `https://` ones.

What it doesn't do: POM profiles are ignored (deps added only inside a profile won't show up), version ranges are picked from metadata with a simple comparator, and it doesn't read Gradle module metadata. If two loads ask for different versions of the same library in the same class loader, the first one wins and you get a warning in the log.

## Settings

```java
new Codinject(Paths.get("libs"))
        .clearRepositories()
        .repository("https://my.mirror/maven2/")
        .target(MyPlugin.class.getClassLoader())
        .logger(getLogger())
        .verifyChecksums(true)
        .allowUnsafe(true);
```

Downloads use the same layout as `~/.m2/repository`. `file:` repositories work too, which is handy for tests.

## Building

```
./gradlew build
```

The library compiles with `--release 8`, and any JDK from 17 up can build it. To run the tests on another Java version:

```
./gradlew test -PtestJava=8
```

CI runs them on 8, 11, 17, 21 and 25.
