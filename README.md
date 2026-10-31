# Skiff

A Cargo/npm-style package manager and build tool for Kotlin. No Gradle, no local `kotlinc`: just a JDK.

```bash
skf new hello
cd hello
skf add com.squareup.okhttp3:okhttp@4.12.0
skf add org.jetbrains.kotlinx:kotlinx-coroutines-core   # latest stable
skf run -- some args
```

## Project layout

```
hello/
├── Skiff.toml      # manifest (you edit this)
├── Skiff.lock      # exact versions + sha256 of every jar (generated, commit it)
├── src/            # Kotlin sources, any package structure
├── test/           # Kotlin tests, run by `skf test`
├── resources/      # optional, copied into the jar
└── target/         # build output (git-ignored)
```

```toml
[package]
name = "hello"
version = "0.1.0"
main = "com.example.MainKt"   # optional; detected from `fun main` (prefers Main.kt)

[kotlin]
version = "2.4.21"            # skiff downloads this compiler version
jvm-target = "25"
plugins = ["serialization", "allopen"]   # optional compiler plugins, see below

[kotlin.allopen]              # allopen, noarg and sam-with-receiver need annotations
annotations = ["com.example.Open"]

[dependencies]
"com.squareup.okhttp3:okhttp" = "4.12.0"
"org.jetbrains.kotlinx:kotlinx-coroutines-core" = { version = "1.11.0" }

[repositories]                # optional; Maven Central is always included
internal = "https://nexus.example.com/repository/maven-public"

[dev-dependencies]            # optional; only on the classpath of test/
"org.mockito:mockito-core" = "5.14.2"
```

### Compiler plugins

`[kotlin] plugins` enables Kotlin compiler plugins; skiff downloads the plugin jar that matches `[kotlin].version`.
Supported: `serialization`, `allopen`, `noarg`, `sam-with-receiver` and `compose`. `allopen`, `noarg` and
`sam-with-receiver` do nothing until you name the annotations they act on in `[kotlin.<plugin>] annotations = [...]`. `serialization` and `compose` also need their
runtime libraries in `[dependencies]` (for example `"org.jetbrains.kotlinx:kotlinx-serialization-json"`, or
`"org.jetbrains.compose.runtime:runtime"`, which needs `google = "https://maven.google.com"` under `[repositories]`).
Plugins apply to `src/` and `test/`. `skf idea` passes the plugin jars and options on to IntelliJ, so the editor
understands what the plugins generate (re-run it after changing `plugins`).

Any other plugin can be added by its Maven coordinates, with raw compiler options passed through as `-P`:

```toml
[[kotlin.compiler-plugin]]
dependency = "dev.example:my-compiler-plugin:1.2.3"
options = ["plugin:dev.example.my:flag=true"]
```

These jars (and their dependencies, minus the Kotlin standard library and compiler) go on the compiler's plugin
classpath only. They are resolved separately from your dependencies and recorded in their own section of
`Skiff.lock`, so they can never change the versions your program uses or end up in its classpath or fat jar.

### Annotation processing (kapt and KSP)

List the processors in their own tables. Processors are resolved separately from your dependencies, like compiler
plugins, so a processor's libraries never leak into your program. The annotations themselves are ordinary
`[dependencies]`.

```toml
[dependencies]
"com.google.auto.service:auto-service-annotations" = "1.1.1"

[kapt-dependencies]           # Java annotation processors, run by kapt
"com.google.auto.service:auto-service" = "1.1.1"

[kapt]                        # optional
correct-error-types = true    # default; replaces generated types in stubs
options = { "some.option" = "value" }

[ksp-dependencies]            # KSP processors
"dev.zacsweers.autoservice:auto-service-ksp" = "1.2.0"

[ksp]                         # optional
version = "2.3.12"            # KSP is versioned separately from Kotlin
options = { "autoservice.verify" = "true" }
```

On `skf build` KSP runs first, then kapt, then the normal compilation sees the generated Kotlin and Java sources.
Generated Java is compiled with the JDK's `javac` (skiff must run on a JDK), and generated resources, such as
`META-INF/services` files, are added to the jar. Output is in `target/generated/`. Processing applies to `src/` only,
and runs on every real build. `skf idea` marks the output directories as generated source roots, so run `skf build`
once and the IDE can see (and navigate into) the generated code.

## Commands

| Command | What it does |
|---|---|
| `skf new <name>` / `skf init` | Create a project with a sample test (new directory / current directory) and `git init` it unless it is already inside a repository or `--no-git` is given |
| `skf add <group:artifact[@version]>...` | Add dependencies (latest stable if no version) and install |
| `skf remove <group:artifact>...` | Remove dependencies |
| `skf install` | Resolve, update `Skiff.lock` if the manifest changed, download jars |
| `skf update` | Re-resolve even if the lockfile is current |
| `skf build [--fat]` | Compile to `target/<name>-<version>.jar` (`--fat`: `-all.jar` with dependencies) |
| `skf run [-- args]` | Build and run |
| `skf test [-- args]` | Compile `test/` and run it with JUnit 5 (kotlin-test is included; args go to the JUnit console launcher) |
| `skf tree` | Dependency tree |
| `skf classpath` | Print the dependency classpath (for IDEs/scripts) |
| `skf vscode` | Generate `workspace.json` for the Kotlin by JetBrains VS Code extension (source roots, jars and JDK; git-ignored; compiler plugins and `jvm-target` are not passed on) |
| `skf idea` | Generate IntelliJ IDEA project files: sources, libraries, Kotlin language level, compiler plugins and generated sources (git-ignored; once generated, `add`, `remove`, `install`, `update` and any build that re-resolves refresh them) |
| `skf clean` | Delete `target/` |
| `skf upgrade [--check]` | Replace the running skiff jar with the latest GitHub release's fat jar (sha256-verified); `--check` only reports. On Windows the swap happens right after the command exits. Needs write access to the install directory, so it fails for the read-only Flatpak (update that with `flatpak`) and may need an elevated shell for the MSI/pkg installs. |

After a successful command skiff prints a one-line hint when a newer release exists, checking GitHub at most once a day
(only at a terminal; set `SKIFF_NO_UPDATE_CHECK` to turn it off, `CI` and `--offline` also skip it).

Global flags: `--locked` (fail if `Skiff.lock` would change, for CI), `--offline` (local cache only).

## Installing skiff

Requires JDK 25+.

```bash
./bootstrap.sh          # or: pwsh ./bootstrap.ps1
```

This writes `bin/skiff.jar`. Put `bin/` on your `PATH` and use `skf` (`skf.cmd` on Windows).
Once bootstrapped, skiff builds itself: `skf build --fat`, then copy `target/skiff-0.1.0-all.jar` to `bin/skiff.jar`.

### Installer packages

Every push runs `.github/workflows/build.yml` (build, tests, fat jar). When that succeeds for `main` or a version tag,
`.github/workflows/package.yml` turns the build's fat jar into the installers below, which you can download from the
workflow run. Pushing a tag `vX.Y.Z` (it must match the version in `Skiff.toml`) also attaches them to a GitHub release.

| Platform | Package | Notes |
|---|---|---|
| Windows | `skf-X.Y.Z.msi` | Bundles its own JDK runtime. Installs to `Program Files\skf` and adds that folder to the system `PATH`; open a new terminal to use `skf`. |
| macOS | `skf-X.Y.Z-arm64.pkg`, `skf-X.Y.Z-x64.pkg` | Signed and notarized when the release was built with the signing secrets (see below); otherwise Gatekeeper blocks it: use System Settings → Privacy & Security → Open Anyway, or `xattr -d com.apple.quarantine <file>`. Installs `skf.app` to `/Applications` and links `skf` into `/usr/local/bin`. |
| Linux | `skiff-X.Y.Z.flatpak` (x86_64) | `flatpak install skiff-X.Y.Z.flatpak`, then `flatpak run io.github.jjones028.Skiff`. It can read your home directory and use the network. |

The Windows and macOS packages come from `packaging/jpackage.sh` (jlink + jpackage); the Flatpak from
`packaging/flatpak/`. macOS packages are versioned 1.x.y while skiff is below 1.0, because macOS rejects a major version of 0.

To sign and notarize the macOS pkg, add these repository secrets (Settings → Secrets and variables → Actions):

| Secret | Value |
|---|---|
| `MACOS_CERTIFICATES_P12` | Base64 of one `.p12` export holding both the *Developer ID Application* and *Developer ID Installer* identities (`base64 -i certs.p12 \| pbcopy`) |
| `MACOS_CERTIFICATES_PASSWORD` | The password chosen when exporting the `.p12` |
| `APPLE_ID` | Apple ID email of the developer account |
| `APPLE_APP_PASSWORD` | An app-specific password for it (appleid.apple.com → Sign-In and Security) |
| `APPLE_TEAM_ID` | The 10-character team ID |

Downloads are cached in `~/.skiff` (override with `SKIFF_HOME`).
