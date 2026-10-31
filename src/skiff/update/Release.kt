package skiff.update

import skiff.SkiffException
import skiff.json.JsonParser

/** A published skiff release: its version and where to download the fat jar and its checksum. */
class Release(val version: String, val jarUrl: String, val checksumUrl: String) {
    companion object {
        /** Reads the GitHub API's description of a release (`tag_name` plus `assets`). */
        fun parse(json: String): Release {
            val release = JsonParser(json).parse() as? Map<*, *> ?: throw SkiffException("unexpected release response")
            val tag = release["tag_name"] as? String ?: throw SkiffException("release has no tag_name")
            val version = tag.removePrefix("v")
            val assets = (release["assets"] as? List<*>).orEmpty()
                .filterIsInstance<Map<*, *>>()
                .associate { it["name"] as? String to it["browser_download_url"] as? String }
            val jar = "skiff-$version-all.jar"
            return Release(
                version,
                assets[jar] ?: throw SkiffException("release $tag has no $jar to download"),
                assets["$jar.sha256"] ?: throw SkiffException("release $tag has no $jar.sha256 to verify the download"),
            )
        }
    }
}
