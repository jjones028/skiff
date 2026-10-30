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
- Runs on JDK 25+ (`jvm-target = "25"`, compiled with `-Xjdk-release=25`, so don't use JDK 26+ APIs
  without reflection).
- Cargo is the UX model: status lines are a right-aligned green verb on stderr (`Term.status`),
  program output goes to stdout, and errors are `SkiffException` (message shown, no stack trace, exit 1).

## Building and testing

```powershell
./bootstrap.ps1                       # builds bin/skiff.jar from scratch
bin/skf.cmd build --fat               # self-host build -> target/skiff-0.1.0-all.jar
```

Unit tests live in `test/` (mirroring the `src/skiff` packages) and run with `bin/skf.cmd test` (the unit tests take about a second; the whole suite about 40 s because of the CLI tests).
Add a test alongside any change to the parsers, version logic, manifest editing or POM resolution. `PomResolverTest`
shows how to resolve fixture POMs from a temp directory with an offline `ArtifactStore`, so tests need no network.

`test/skiff/e2e` drives the real CLI in a child JVM (`Skf`): `CliBasicsTest` needs no network, while
`CliProjectLifecycleTest` runs new/run/add/lock/fat jar/test/idea/remove on a temp project, so the first run needs
network access to fill `~/.skiff` (compiler, gson, JUnit). It shares one project because every compile takes seconds.

## Architecture (src/skiff)

| Package | Role |
|---|---|
| `cli` | `Cli` parses args and dispatches commands; `Term` does Cargo-style output |
| `toml`, `json` | Minimal parsers (TOML subset without dates; JSON for Gradle `.module` files) |
| `maven` | `ArtifactStore` (HTTP + `~/.skiff/cache/maven`, sha1-verified), `Pom`/`PomResolver` (parents, `${}` interpolation, dependencyManagement + BOM imports), `DependencyResolver` (graph walk), `Version`/`VersionRange`, `MavenMetadata` (latest/ranges), `GradleModule` (KMP JVM redirect) |
| `project` | `Manifest` (Skiff.toml), `ManifestEditor` (text edits that keep comments), `Lockfile`/`LockedPackage`, `Installer` (manifest -> lock -> jars), `IdeaProject` (`skf idea`: generated, git-ignored IntelliJ module), `VsCodeProject` (`skf vscode`: `workspace.json` for kotlin-lsp's JSON import, schema from its `model.kt`; not yet verified in a live VS Code), `ProjectTemplate` |
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
- **Compiler plugins** (`[kotlin] plugins`, `KotlinPlugin`/`PluginConfig`): each plugin is one dependency-free
  `*-compiler-plugin-embeddable` jar at the Kotlin version. `Toolchain.pluginJars` fetches it into the Maven cache
  (not locked, like the compiler itself) and `Builder` passes `-Xplugin=<jar>` plus `-P plugin:<id>:annotation=<a>`
  for both `src/` and `test/`. The plugins and their annotations are part of the build stamp. Presets such as
  `spring`/`jpa` are not implemented. `compose` is a named plugin with no options.
  `[[kotlin.compiler-plugin]]` (`ExternalPlugin`) is the generic form for any other plugin: coordinates plus raw `-P`
  options. Their artifacts are an isolated group (see below); `Installer.pluginClasspath` drops kotlin-stdlib and the
  compiler from it, since the compiler JVM already has them.
- **Isolated resolution** (`Manifest.isolatedRoots`, `Lockfile.isolated`): compiler plugins (`plugins`), kapt
  processors (`kapt`) and KSP processors (`ksp`) are each resolved by their own `DependencyResolver.resolve` call and
  stored in `Skiff.lock` as `[[package]]` entries with a `configuration` key, so their versions can never change the
  program's classpath (the main entries have no `configuration`). They are part of the fingerprint, listed by
  `Installer.isolatedClasspath`, and never reach `classpath`, `tree` or fat jars.
- **Annotation processing** (`[kapt-dependencies]`/`[kapt]`, `[ksp-dependencies]`/`[ksp]`, `AnnotationProcessing`,
  `KaptConfig`/`KspConfig`) runs inside `Builder.build` before the main compile, for `src/` only (not `test/`):
  1. KSP: a child JVM runs `KSPJvmMain` (`symbol-processing-aa-embeddable`, resolved and cached by
     `Toolchain.kspClasspath`; KSP has its own version, default `KspConfig.DEFAULT_VERSION`) with the processor jars as
     the last argument. Output goes to `target/generated/ksp/{kotlin,java,classes,resources}`.
  2. kapt: an extra compiler pass with `-Xplugin=kotlin-annotation-processing-embeddable` and
     `-P plugin:org.jetbrains.kotlin.kapt3:aptMode=stubsAndApt`. In that mode kapt compiles nothing; it writes stubs
     and generated sources (`target/generated/kapt/{sources,classes,kotlin}`), so the main compile is a second pass.
     All processor options travel in one Base64 `apoptions` value (`AnnotationProcessing.encodeOptions`);
     `apOption` can be given only once. `kapt.kotlin.generated` points processors at `target/generated/kapt/kotlin`.
  3. The main compile gets the original and generated Kotlin and Java sources (kotlinc only reads the Java);
     `JavaCompilation` then compiles generated Java with the JDK's `javac` (`-proc:none`) into the same classes
     directory, and generated resources and class files are copied into it.
  Everything is regenerated on each real build (the stamp covers the processors and options). Known gaps: no incremental
  processing and no processing for `test/`.
- **IDE** (`IdeaProject`): besides roots and libraries, `skf idea` writes the module's Kotlin facet in the V5 format
  the Kotlin plugin uses (`CompilerArgumentsSerializerV5`): `jvmTarget`, `languageVersion`/`apiVersion` as string
  arguments, and `pluginClasspaths` / `pluginOptions` as array arguments (one value is the text of `<args>`, several
  are `<arg>` children). That is how the K2 IDE finds compiler plugins. kapt and KSP output directories become source
  roots with `generated="true"` and are created up front so the IDE picks them up. The format was checked by feeding
  the generated `<configuration>` to IntelliJ's own `FacetSerializationKt.deserializeFacetSettings` (from the
  installed Kotlin plugin); `IdeaProjectFacetTest` checks it structurally.
- **Editor sync**: `Cli.syncEditors` regenerates the IDEA module and `workspace.json` after `add`/`remove`/`install`/`update`
  (and after `build`/`run`/`test` when the lockfile changed), but only for files that already exist, so projects that
  never ran `skf idea`/`skf vscode` get nothing written. Any new command that changes the manifest or lock should call it.
- The compiler runs in a **child JVM** on the same `java.home` as skiff, with arguments in
  `target/kotlinc.args` / `target/kotlinc.jvm.args`, which helps with debugging failed builds.

## Known gaps / roadmap (rough priority)

1. **Gradle module metadata constraints**: Gradle-published `dependencyConstraints` (e.g. kotlin-stdlib
   aligning `-jdk7`/`-jdk8`) are ignored, so old `kotlin-stdlib-jdk7/jdk8 1.9.x` jars can show up. Read
   constraints from `.module` and apply them as version floors.
2. **Compile speed**: each build spawns a new JVM (about 3–9 s). Options: run `K2JVMCompiler` in-process
   through an isolated `URLClassLoader`, or keep a compiler daemon running between builds. Real
   incremental compilation could come later through `kotlin-build-tools-api`.
3. **Java sources** in `src/`: only generated Java is compiled so far (`JavaCompilation`); hand-written `.java` files
   in `src/` still need to be passed to kotlinc and then to javac.
4. **Private repositories with auth** (`[repositories]` credentials from env vars or `~/.skiff/config.toml`),
   and `skf publish` (POM generation, sources/javadoc jars, signing).
5. **Classifiers and non-jar types** (currently skipped with a warning), and Maven's `<relocation>`.
6. **Workspaces** (multi-module projects, like Cargo workspaces) and `[scripts]` (like `npm run <script>`).
7. **Distribution**: installers exist (`build.yml` uploads the fat jar, `package.yml` (workflow_run) makes jpackage MSI/DMG and Flatpak from it; unsigned, not on PATH, never run on CI yet). Still open: `skf self-update`, GraalVM native-image or jlink build, Homebrew/Scoop/winget packages.
