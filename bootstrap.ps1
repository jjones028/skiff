# Builds bin/skiff.jar without Gradle or a local kotlinc: downloads the Kotlin compiler jars from
# Maven Central into skiff's cache, compiles src/, and bundles the Kotlin stdlib into the jar.
# After this, skiff can rebuild itself with `bin/skf build --fat`.
$ErrorActionPreference = 'Stop'

$KotlinVersion = '2.4.21'
$JvmTarget = '21'
$Root = $PSScriptRoot
$SkiffHome = if ($env:SKIFF_HOME) { $env:SKIFF_HOME } else { Join-Path $HOME '.skiff' }
$Cache = Join-Path $SkiffHome 'cache/maven'
$Central = 'https://repo.maven.apache.org/maven2'
$JavaBin = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin' } else { Split-Path (Get-Command java).Source }

# The runtime classpath of org.jetbrains.kotlin:kotlin-compiler-embeddable (see its POM).
$Compiler = @(
    "org.jetbrains.kotlin:kotlin-compiler-embeddable:$KotlinVersion",
    "org.jetbrains.kotlin:kotlin-build-tools-api:$KotlinVersion",
    "org.jetbrains.kotlin:kotlin-stdlib:$KotlinVersion",
    "org.jetbrains.kotlin:kotlin-script-runtime:$KotlinVersion",
    "org.jetbrains.kotlin:kotlin-daemon-embeddable:$KotlinVersion",
    "org.jetbrains.kotlin:kotlin-reflect:1.6.10",
    "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.8.0",
    "org.jetbrains:annotations:13.0"
)

function Get-Jar([string] $Coordinate) {
    $group, $artifact, $version = $Coordinate.Split(':')
    $relative = "$($group.Replace('.', '/'))/$artifact/$version/$artifact-$version.jar"
    $file = Join-Path $Cache $relative
    if (-not (Test-Path $file)) {
        New-Item -ItemType Directory -Force (Split-Path $file) | Out-Null
        Write-Host "  Downloading $artifact-$version.jar"
        $part = "$file.part"
        Invoke-WebRequest "$Central/$relative" -OutFile $part -UseBasicParsing
        $expected = (Invoke-WebRequest "$Central/$relative.sha1" -UseBasicParsing).Content
        if ($expected -is [byte[]]) { $expected = [Text.Encoding]::ASCII.GetString($expected) }
        $expected = $expected.Trim().Split(' ')[0].ToLower()
        $actual = (Get-FileHash -Algorithm SHA1 $part).Hash.ToLower()
        if ($expected -ne $actual) { Remove-Item $part; throw "checksum mismatch for $relative" }
        Move-Item -Force $part $file
    }
    return $file
}

$compilerClasspath = ($Compiler | ForEach-Object { Get-Jar $_ }) -join [IO.Path]::PathSeparator
$stdlib = Get-Jar "org.jetbrains.kotlin:kotlin-stdlib:$KotlinVersion"

$out = Join-Path $Root 'build/bootstrap'
$classes = Join-Path $out 'classes'
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force $classes | Out-Null

# JDK 24+ warns about sun.misc.Unsafe use in the compiler; the option does not exist before that.
$prev = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
$spec = (& (Join-Path $JavaBin 'java') -XshowSettings:properties -version 2>&1 | Out-String)
$ErrorActionPreference = $prev
$unsafeFlag = @(if ($spec -match 'java\.specification\.version = (\d+)' -and [int]$Matches[1] -ge 24) { '--sun-misc-unsafe-memory-access=allow' })
$sources = Get-ChildItem -Recurse -Filter *.kt (Join-Path $Root 'src') | ForEach-Object { $_.FullName }
Write-Host "  Compiling skiff ($($sources.Count) files)"
$javaArgs = $unsafeFlag + @('-cp', $compilerClasspath, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
    '-d', $classes, '-classpath', $stdlib, '-no-stdlib', '-no-reflect', '-jvm-target', $JvmTarget, "-Xjdk-release=$JvmTarget", '-module-name', 'skiff') + $sources
& (Join-Path $JavaBin 'java') @javaArgs 2>&1 | ForEach-Object { Write-Host $_ }
if ($LASTEXITCODE -ne 0) { throw 'compilation failed' }

# Bundle the Kotlin stdlib so `java -jar skiff.jar` works on its own.
Push-Location $classes
try { & (Join-Path $JavaBin 'jar') xf $stdlib } finally { Pop-Location }
Remove-Item -Recurse -Force (Join-Path $classes 'META-INF/MANIFEST.MF'), (Join-Path $classes 'META-INF/versions') -ErrorAction SilentlyContinue

$jar = Join-Path $Root 'bin/skiff.jar'
& (Join-Path $JavaBin 'jar') --create --file $jar --main-class skiff.MainKt -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'jar failed' }
Write-Host "  Built $jar"
