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
jvm-target = "21"

[dependencies]
"com.squareup.okhttp3:okhttp" = "4.12.0"
"org.jetbrains.kotlinx:kotlinx-coroutines-core" = { version = "1.11.0" }

[repositories]                # optional; Maven Central is always included
internal = "https://nexus.example.com/repository/maven-public"

[dev-dependencies]            # optional; only on the classpath of test/
"org.mockito:mockito-core" = "5.14.2"
```

## Commands

| Command | What it does |
|---|---|
| `skf new <name>` / `skf init` | Create a project (new directory / current directory) |
| `skf add <group:artifact[@version]>...` | Add dependencies (latest stable if no version) and install |
| `skf remove <group:artifact>...` | Remove dependencies |
| `skf install` | Resolve, update `Skiff.lock` if the manifest changed, download jars |
| `skf update` | Re-resolve even if the lockfile is current |
| `skf build [--fat]` | Compile to `target/<name>-<version>.jar` (`--fat`: `-all.jar` with dependencies) |
| `skf run [-- args]` | Build and run |
| `skf test [-- args]` | Compile `test/` and run it with JUnit 5 (kotlin-test is included; args go to the JUnit console launcher) |
| `skf tree` | Dependency tree |
| `skf classpath` | Print the dependency classpath (for IDEs/scripts) |
| `skf clean` | Delete `target/` |

Global flags: `--locked` (fail if `Skiff.lock` would change, for CI), `--offline` (local cache only).

## Installing skiff

Requires JDK 21+.

```bash
./bootstrap.sh          # or: pwsh ./bootstrap.ps1
```

This writes `bin/skiff.jar`. Put `bin/` on your `PATH` and use `skf` (`skf.cmd` on Windows).
Once bootstrapped, skiff builds itself: `skf build --fat`, then copy `target/skiff-0.1.0-all.jar` to `bin/skiff.jar`.

Downloads are cached in `~/.skiff` (override with `SKIFF_HOME`).
