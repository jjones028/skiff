package skiff.maven

import skiff.BuildInfo
import skiff.Hashing
import skiff.SkiffException
import skiff.SkiffHome
import java.io.IOException
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/**
 * Downloads files from Maven repositories into a shared local cache (Maven repository layout),
 * verifying each download against the repository's `.sha1` checksum when one is published.
 */
class ArtifactStore(
    private val repositories: List<Repository>,
    private val offline: Boolean = false,
    private val cacheDir: Path = SkiffHome.mavenCache,
) {
    /** Called with the repository path of every file that is actually downloaded (not served from cache). */
    var onDownload: (String) -> Unit = {}

    private val http: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(20))
        .proxy(ProxySelector.getDefault())
        .build()
    private val missing: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Returns the cached copy of [path], downloading it first if needed, or null if no repository has it. */
    fun get(path: String): Path? {
        val local = cacheDir.resolve(path)
        if (Files.isRegularFile(local)) return local
        if (offline || path in missing) return null
        for (repository in repositories) {
            if (download(repository, path, local)) return local
        }
        missing += path
        return null
    }

    /** Like [get], but asks the repositories again even when cached. For files that change, like `maven-metadata.xml`. */
    fun refresh(path: String): Path? {
        val local = cacheDir.resolve(path)
        if (!offline) {
            for (repository in repositories) {
                if (download(repository, path, local)) return local
            }
        }
        return local.takeIf { Files.isRegularFile(it) }
    }

    /** Fetches several files in parallel, failing if any of them cannot be found. */
    fun getAll(paths: Collection<String>): Map<String, Path> {
        if (paths.isEmpty()) return emptyMap()
        val pool = Executors.newFixedThreadPool(minOf(8, paths.size))
        try {
            val futures = paths.associateWith { path -> pool.submit<Path?> { get(path) } }
            return futures.mapValues { (path, future) ->
                val file = try {
                    future.get()
                } catch (e: ExecutionException) {
                    throw e.cause ?: e
                }
                file ?: throw notFound(path)
            }
        } finally {
            pool.shutdownNow()
        }
    }

    fun notFound(what: String): SkiffException =
        SkiffException("could not find $what in any repository" + if (offline) " (running offline)" else "")

    private fun download(repository: Repository, path: String, target: Path): Boolean {
        val body = fetch("${repository.baseUrl}/$path") ?: return false
        val expectedSha1 = fetch("${repository.baseUrl}/$path.sha1")
            ?.toString(Charsets.US_ASCII)?.trim()?.substringBefore(' ')?.lowercase()
        if (expectedSha1 != null && expectedSha1.length == 40 && Hashing.sha1(body) != expectedSha1) {
            throw SkiffException("checksum mismatch for $path from ${repository.name}")
        }
        Files.createDirectories(target.parent)
        val temp = Files.createTempFile(target.parent, target.fileName.toString(), ".part")
        try {
            Files.write(temp, body)
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
        onDownload(path)
        return true
    }

    private fun fetch(url: String): ByteArray? {
        val request = HttpRequest.newBuilder(URI.create(url))
            .header("User-Agent", "skiff/${BuildInfo.VERSION}")
            .timeout(Duration.ofMinutes(5))
            .GET()
            .build()
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofByteArray())
        } catch (e: IOException) {
            throw SkiffException("could not reach $url: ${e.message ?: e.javaClass.simpleName}", e)
        }
        return when (response.statusCode()) {
            200 -> response.body()
            404, 410 -> null
            else -> throw SkiffException("GET $url failed with HTTP ${response.statusCode()}")
        }
    }
}
