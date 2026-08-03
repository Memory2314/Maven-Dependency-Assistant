package io.github.memory2314.mavendependencyassistant.maven

import io.github.memory2314.mavendependencyassistant.settings.MavenDependencySettings
import com.google.gson.Gson
import com.intellij.util.io.HttpRequests
import java.io.StringReader
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

internal data class MavenArtifact(val groupId: String, val artifactId: String, val latestVersion: String)
internal data class ArtifactSearchPage(val artifacts: List<MavenArtifact>, val total: Int, val start: Int)
internal data class MavenVersion(val value: String, val date: String?)

internal enum class BuildTool(private val displayName: String) {
    MAVEN("Maven"),
    GRADLE("Gradle"),
    SBT("SBT"),
    MILL("Mill"),
    IVY("Ivy"),
    GRAPE("Grape"),
    LEININGEN("Leiningen"),
    BUILDR("Buildr");

    override fun toString() = displayName
}

internal enum class GradleFormat(private val displayName: String) {
    GROOVY_LONG("Groovy Long"),
    GROOVY_SHORT("Groovy Short"),
    KOTLIN("Kotlin");

    override fun toString() = displayName
}

internal enum class SearchMode {
    JAR,
    CLASS,
}

internal object MavenCentralClient {
    private val gson = Gson()
    private val versionCache = ConcurrentHashMap<String, List<MavenVersion>>()
    private val searchCache = ConcurrentHashMap<SearchCacheKey, ArtifactSearchPage>()

    fun searchArtifacts(query: String, start: Int, rows: Int, mode: SearchMode): ArtifactSearchPage {
        val cacheKey = SearchCacheKey(query, start, rows, mode)
        return searchCache.computeIfAbsent(cacheKey) {
            val coordinateParts = query.split(':', limit = 2)
                .takeIf { mode == SearchMode.JAR && it.size == 2 && it.all(String::isNotBlank) }
            val searchExpression = when (mode) {
                SearchMode.CLASS -> "c:$query"
                SearchMode.JAR -> coordinateParts?.let {
                    "g:${it[0].trim()} AND a:${it[1].trim()}"
                } ?: query
            }
            val response = request(mapOf(
                "q" to searchExpression,
                "start" to (start / rows).toString(),
                "rows" to rows.toString(),
                "wt" to "json",
            )).response
            val artifacts = response.docs.mapNotNull { doc ->
                val groupId = doc.g ?: return@mapNotNull null
                val artifactId = doc.a ?: return@mapNotNull null
                MavenArtifact(groupId, artifactId, doc.latestVersion ?: doc.v.orEmpty())
            }.distinctBy { it.groupId to it.artifactId }
            val total = if (coordinateParts == null) response.numFound else artifacts.size
            ArtifactSearchPage(artifacts, total, start)
        }
    }

    fun findVersions(groupId: String, artifactId: String): List<MavenVersion> {
        val cacheKey = "$groupId:$artifactId"
        return versionCache.computeIfAbsent(cacheKey) {
            val groupPath = groupId.split('.').joinToString("/") { encodePathSegment(it) }
            val artifactUrl = "${MavenDependencySettings.repositoryUrl.trimEnd('/')}/$groupPath/${encodePathSegment(artifactId)}"
            val xml = HttpRequests.request("$artifactUrl/maven-metadata.xml")
                .userAgent(USER_AGENT)
                .connectTimeout(10_000)
                .readTimeout(15_000)
                .readString()
            val dates = runCatching {
                val html = HttpRequests.request("$artifactUrl/")
                    .userAgent(USER_AGENT)
                    .connectTimeout(10_000)
                    .readTimeout(15_000)
                    .readString()
                MavenRepositoryDirectoryParser.parseDates(html)
            }.getOrDefault(emptyMap())
            MavenMetadataParser.parseVersions(xml).map { MavenVersion(it, dates[it]) }
        }
    }

    fun cachedSearchPageCount(query: String, rows: Int, mode: SearchMode, totalResults: Int): Int =
        searchCache.keys.count { key ->
            key.query == query && key.rows == rows && key.mode == mode && key.start < totalResults
        }

    fun retainSearchCache(query: String, rows: Int, mode: SearchMode) {
        searchCache.keys.removeIf { key ->
            key.query != query || key.rows != rows || key.mode != mode
        }
    }

    fun clearCaches() {
        searchCache.clear()
        versionCache.clear()
    }

    private fun request(parameters: Map<String, String>): SearchEnvelope {
        val query = parameters.entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }
        val json = HttpRequests.request("${MavenDependencySettings.searchUrl}?$query")
            .userAgent(USER_AGENT)
            .connectTimeout(10_000)
            .readTimeout(15_000)
            .readString()
        return gson.fromJson(json, SearchEnvelope::class.java)
    }

    private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)
    private fun encodePathSegment(value: String) = encode(value).replace("+", "%20")

    private data class SearchEnvelope(val response: SearchResponse = SearchResponse())
    private data class SearchResponse(
        val numFound: Int = 0,
        val start: Int = 0,
        val docs: List<SearchDocument> = emptyList(),
    )
    private data class SearchDocument(
        val g: String? = null,
        val a: String? = null,
        val v: String? = null,
        val latestVersion: String? = null,
    )
    private data class SearchCacheKey(
        val query: String,
        val start: Int,
        val rows: Int,
        val mode: SearchMode,
    )

    private const val USER_AGENT = "Maven Dependency Assistant IntelliJ Plugin"
}

internal object MavenRepositoryDirectoryParser {
    private val versionEntry = Regex(
        """<a\s+href="([^"]+)/"[^>]*>.*?</a>\s+(\d{4}-\d{2}-\d{2})""",
        RegexOption.IGNORE_CASE,
    )

    fun parseDates(html: String): Map<String, String> = buildMap {
        versionEntry.findAll(html).forEach { match ->
            val version = URLDecoder.decode(match.groupValues[1], StandardCharsets.UTF_8)
            if (version != "..") put(version, match.groupValues[2])
        }
    }
}

internal object MavenMetadataParser {
    fun parseVersions(xml: String): List<String> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            isXIncludeAware = false
            setExpandEntityReferences(false)
        }
        val document = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        val versionNodes = document.getElementsByTagName("version")
        val versions = buildList {
            for (index in 0 until versionNodes.length) {
                versionNodes.item(index).textContent?.trim()?.takeIf(String::isNotEmpty)?.let(::add)
            }
        }.distinct()
        val release = document.getElementsByTagName("release").item(0)?.textContent?.trim()
        val latest = document.getElementsByTagName("latest").item(0)?.textContent?.trim()
        val preferred = release?.takeIf(String::isNotEmpty) ?: latest?.takeIf(String::isNotEmpty)
        return buildList {
            preferred?.let(::add)
            addAll(versions.asReversed())
        }.distinct()
    }
}

internal object DependencySnippet {
    fun maven(groupId: String, artifactId: String, version: String, scope: String): String = """
        <dependency>
            <groupId>${xml(groupId)}</groupId>
            <artifactId>${xml(artifactId)}</artifactId>
            <version>${xml(version)}</version>
            <scope>${xml(scope)}</scope>
        </dependency>
    """.trimIndent()

    fun gradle(
        groupId: String,
        artifactId: String,
        version: String,
        scope: String,
        format: GradleFormat,
    ): String {
        val configuration = when (scope) {
            "test" -> "testImplementation"
            "runtime" -> "runtimeOnly"
            "provided" -> "compileOnly"
            else -> "implementation"
        }
        return when (format) {
            GradleFormat.GROOVY_LONG -> "$configuration group: '${groovy(groupId)}', name: '${groovy(artifactId)}', version: '${groovy(version)}'"
            GradleFormat.GROOVY_SHORT -> "$configuration '${groovy("$groupId:$artifactId:$version")}'"
            GradleFormat.KOTLIN -> "$configuration(\"${kotlin("$groupId:$artifactId:$version")}\")"
        }
    }

    fun sbt(groupId: String, artifactId: String, version: String, scope: String): String =
        "libraryDependencies += \"${doubleQuoted(groupId)}\" % \"${doubleQuoted(artifactId)}\" % " +
            "\"${doubleQuoted(version)}\" % ${sbtScope(scope)}"

    fun mill(groupId: String, artifactId: String, version: String): String =
        "ivy\"${doubleQuoted("$groupId:$artifactId:$version")}\""

    fun ivy(groupId: String, artifactId: String, version: String): String =
        "<dependency org=\"${xml(groupId)}\" name=\"${xml(artifactId)}\" rev=\"${xml(version)}\"/>"

    fun grape(groupId: String, artifactId: String, version: String): String = """
        @Grapes(
            @Grab(group='${groovy(groupId)}', module='${groovy(artifactId)}', version='${groovy(version)}')
        )
    """.trimIndent()

    fun leiningen(groupId: String, artifactId: String, version: String): String =
        "[$groupId/$artifactId \"${doubleQuoted(version)}\"]"

    fun buildr(groupId: String, artifactId: String, version: String): String =
        "'${groovy(groupId)}:${groovy(artifactId)}:jar:${groovy(version)}'"

    private fun sbtScope(scope: String) = scope.replaceFirstChar { it.uppercase() }

    private fun xml(value: String) = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun groovy(value: String) = value.replace("\\", "\\\\").replace("'", "\\'")
    private fun kotlin(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"")
    private fun doubleQuoted(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"")
}
