# Skiff: notes for continuing development

Skiff (`skf`) is a Cargo/npm-style package manager and build tool for Kotlin/JVM that replaces Gradle for
simple projects. User-facing docs are in README.md; this file records design decisions and what's next.

## Ground rules

- **Zero runtime dependencies.** Skiff uses only the JDK and kotlin-stdlib (TOML, JSON, Maven resolution
  and HTTP are hand-written or from `java.*`). This keeps bootstrap trivial: `bootstrap.ps1`/`bootstrap.sh`
  compile `src/` with the Kotlin compiler jars downloaded straight from Maven Central. Adding a dependency
  means the bootstrap scripts must download it too, so avoid that.
- **One top-level class/object per file**, file named after it. Nested types are fine.
- Skiff must stay **self-hosting**: `bin/skf build --fat` in this repo must keep working.
- Runs on JDK 21+ (`jvm-target = "21"`, compiled with `-Xjdk-release=21`, so don't use JDK 22+ APIs
  without reflection; see `Term.isTerminal`).
- Cargo is the UX model: status lines are a right-aligned green verb on stderr (`Term.status`),
  program output goes to stdout, and errors are `SkiffException` (message shown, no stack trace, exit 1).

## Building and testing

```powershell
./bootstrap.ps1                       # builds bin/skiff.jar from scratch
bin/skf.cmd build --fat               # self-host build -> target/skiff-0.1.0-all.jar
```

Unit tests live in `test/` (mirroring the `src/skiff` packages) and run with `bin/skf.cmd test` (about 1 s once compiled).
Add a test alongside any change to the parsers, version logic, manifest editing or POM resolution. `PomResolverTest`
shows how to resolve fixture POMs from a temp directory with an offline `ArtifactStore`, so tests need no network.

There is no automated end-to-end test yet. The manual check used so far (run in a short temp path;
`cmd.exe` cannot start in paths longer than about 260 characters):

```powershell
skf new hello; cd hello
skf add com.squareup.okhttp3:okhttp@4.12.0 org.jetbrains.kotlinx:kotlinx-coroutines-core
skf run -- a b; skf run          # second run should print "Fresh"
skf tree; skf build --fat; skf install --locked; skf run --offline
```

## Architecture (src/skiff)

| Package | Role |
|---|---|
| `cli` | `Cli` parses args and dispatches commands; `Term` does Cargo-style output |
| `toml`, `json` | Minimal parsers (TOML subset without dates; JSON for Gradle `.module` files) |
| `maven` | `ArtifactStore` (HTTP + `~/.skiff/cache/maven`, sha1-verified), `Pom`/`PomResolver` (parents, `${}` interpolation, dependencyManagement + BOM imports), `DependencyResolver` (graph walk), `Version`/`VersionRange`, `MavenMetadata` (latest/ranges), `GradleModule` (KMP JVM redirect) |
| `project` | `Manifest` (Skiff.toml), `ManifestEditor` (text edits that keep comments), `Lockfile`/`LockedPackage`, `Installer` (manifest -> lock -> jars), `ProjectTemplate` |
| `build` | `Toolchain` (resolves `kotlin-compiler-embeddable` like any other dependency, caches its classpath in `~/.skiff/toolchains`), `Builder` (child JVM running `K2JVMCompiler` via argfiles, then jar), `JarWriter`, `MainClassDetector`, `Runner`, `JavaProcess` |

### Key decisions

- **Conflict resolution is highest-version-wins** (like Gradle, not Maven's nearest-wins), iterated to a
  fixpoint because raising a version can change that module's dependencies. Only `compile`/`runtime`
  scopes are followed; optional dependencies are skipped. Exclusions accumulate along the path.
- **kotlin-stdlib is an implicit dependency** at `[kotlin].version` and is locked like any other package.
- **Kotlin Multiplatform**: if a POM carries Gradle's `published-with-gradle-metadata` marker, the
  `.module` file is read, and a `java-runtime` variant with `available-at` turns the root module into a
  jar-less node pointing at e.g. `kotlinx-coroutines-core-jvm`.
- **Lockfile freshness** is a sha256 fingerprint of the deps, Kotlin version and repositories
  (`Manifest.resolutionFingerprint`). If it matches, nothing is resolved; jars are just verified against
  the locked sha256.
- **Incremental build** is all-or-nothing: `target/.build-stamp` hashes the source paths, sizes, mtimes,
  classpath and settings. Any change recompiles everything.
- **Tests**: `test/*.kt` is compiled into `target/test-classes` against the main classes plus `[dev-dependencies]`, then run
  with the JUnit Platform console launcher (`TestRunner`). When `test/` has Kotlin sources, `kotlin-test-junit5`,
  `junit-jupiter-engine` and `junit-platform-console` become extra roots (`Manifest.testDependencies`); versions are
  pinned in `Manifest`. Dev roots are resolved and locked together with the real dependencies (so versions unify),
  but `Installer.install(tests = false)` only returns jars reachable from the real roots, which keeps them out of
  builds and fat jars. The fingerprint is unchanged for projects without dev roots.
- The compiler runs in a **child JVM** on the same `java.home` as skiff, with arguments in
  `target/kotlinc.args` / `target/kotlinc.jvm.args`, which helps with debugging failed builds.

## Known gaps / roadmap (rough priority)

1. **More tests**: `skf test` and unit tests for the pure logic exist; still missing are tests for `JsonParser`,
   `GradleModule` (only covered through `DependencyResolverTest`), `Lockfile`,
   `MainClassDetector`, and an automated end-to-end test of the CLI.
2. **Gradle module metadata constraints**: Gradle-published `dependencyConstraints` (e.g. kotlin-stdlib
   aligning `-jdk7`/`-jdk8`) are ignored, so old `kotlin-stdlib-jdk7/jdk8 1.9.x` jars can show up. Read
   constraints from `.module` and apply them as version floors.
3. **Compile speed**: each build spawns a new JVM (about 3–9 s). Options: run `K2JVMCompiler` in-process
   through an isolated `URLClassLoader`, or keep a compiler daemon running between builds. Real
   incremental compilation could come later through `kotlin-build-tools-api`.
4. **Java sources** in `src/` (run javac via `ToolProvider` after kotlinc).
5. **Private repositories with auth** (`[repositories]` credentials from env vars or `~/.skiff/config.toml`),
   and `skf publish` (POM generation, sources/javadoc jars, signing).
6. **Classifiers and non-jar types** (currently skipped with a warning), and Maven's `<relocation>`.
7. **Workspaces** (multi-module projects, like Cargo workspaces) and `[scripts]` (like `npm run <script>`).
8. **Distribution**: `skf self-update`, GraalVM native-image or jlink build, Homebrew/Scoop/winget packages.
9. Plugins: compiler plugins (`kotlinx-serialization`, `allopen`, `noarg`) via `[kotlin] plugins = [...]`.
   This matters for real-world use, since serialization is common.
