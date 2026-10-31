package skiff.update

import skiff.BuildInfo
import skiff.Hashing
import skiff.SkiffException
import java.io.IOException
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/** Talks to GitHub for skiff's releases. `SKIFF_RELEASES_URL` overrides the "latest release" endpoint. */
open class Releases(
    private val latestUrl: String = System.getenv("SKIFF_RELEASES_URL") ?: DEFAULT_URL,
    private val timeout: Duration = Duration.ofSeconds(30),
) {
    private val http: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(timeout)
        .proxy(ProxySelector.getDefault())
        .build()

    /** The newest non-prerelease release. */
    open fun latest(): Release = Release.parse(send(latestUrl, HttpResponse.BodyHandlers.ofString()))

    /** Downloads [release]'s fat jar to [target] and checks it against the published sha256. */
    fun downloadJar(release: Release, target: Path) {
        val expected = send(release.checksumUrl, HttpResponse.BodyHandlers.ofString()).trim().substringBefore(' ').lowercase()
        Files.deleteIfExists(target)
        send(release.jarUrl, HttpResponse.BodyHandlers.ofFile(target))
        if (Hashing.sha256(target) != expected) {
            Files.deleteIfExists(target)
            throw SkiffException("checksum mismatch for ${release.jarUrl}")
        }
    }

    private fun <T> send(url: String, handler: HttpResponse.BodyHandler<T>): T {
        val request = HttpRequest.newBuilder(URI.create(url))
            .header("User-Agent", "skiff/${BuildInfo.VERSION}")
            .header("Accept", "application/vnd.github+json")
            .timeout(timeout)
            .GET()
            .build()
        val response = try {
            http.send(request, handler)
        } catch (e: IOException) {
            throw SkiffException("could not reach $url: ${e.message ?: e.javaClass.simpleName}", e)
        }
        if (response.statusCode() != 200) throw SkiffException("GET $url failed with HTTP ${response.statusCode()}")
        return response.body()
    }

    companion object {
        const val DEFAULT_URL = "https://api.github.com/repos/jjones028/skiff/releases/latest"
    }
}
