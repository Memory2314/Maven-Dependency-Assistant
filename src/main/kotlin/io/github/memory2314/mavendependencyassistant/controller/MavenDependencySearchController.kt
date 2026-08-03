package io.github.memory2314.mavendependencyassistant.controller

import io.github.memory2314.mavendependencyassistant.MyMessageBundle.message
import io.github.memory2314.mavendependencyassistant.maven.ArtifactSearchPage
import io.github.memory2314.mavendependencyassistant.maven.BuildTool
import io.github.memory2314.mavendependencyassistant.maven.DependencySnippet
import io.github.memory2314.mavendependencyassistant.maven.MavenArtifact
import io.github.memory2314.mavendependencyassistant.maven.MavenCentralClient
import io.github.memory2314.mavendependencyassistant.maven.SearchMode
import io.github.memory2314.mavendependencyassistant.settings.MavenDependencyConfigurable
import io.github.memory2314.mavendependencyassistant.settings.MavenDependencySettings
import io.github.memory2314.mavendependencyassistant.ui.MavenDependencySearchCallbacks
import io.github.memory2314.mavendependencyassistant.ui.MavenDependencySearchView
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import java.awt.datatransfer.StringSelection
import java.util.concurrent.atomic.AtomicInteger

internal class MavenDependencySearchController(
    private val project: Project,
    private val view: MavenDependencySearchView,
) : MavenDependencySearchCallbacks {
    private val searchRequestSequence = AtomicInteger()
    private val versionRequestSequence = AtomicInteger()
    private val preloadGeneration = AtomicInteger()
    private var currentQuery = ""
    private var currentSearchMode = SearchMode.JAR
    private var activePreload: PreloadKey? = null
    @Volatile private var activePreloadIndicator: ProgressIndicator? = null
    @Volatile private var activeSearchIndicator: ProgressIndicator? = null
    private var currentStart = 0
    private var totalResults = 0

    init {
        view.bind(this)
        if (MavenDependencySettings.rememberQuery) {
            view.restoreQuery(PropertiesComponent.getInstance(project).getValue(LAST_QUERY_KEY).orEmpty())
        }
    }

    override fun search() {
        val query = view.searchQuery
        if (query.isEmpty()) {
            view.showStatus(message("status.queryRequired"))
            view.requestSearchFocus()
            return
        }
        val mode = view.searchMode
        if (query != currentQuery || mode != currentSearchMode) {
            preloadGeneration.incrementAndGet()
            activePreloadIndicator?.cancel()
            activePreload = null
            MavenCentralClient.retainSearchCache(query, PAGE_SIZE, mode)
        }
        if (MavenDependencySettings.rememberQuery) {
            PropertiesComponent.getInstance(project).setValue(LAST_QUERY_KEY, query)
        }
        currentQuery = query
        currentSearchMode = mode
        loadArtifactPage(query, 0, currentSearchMode)
    }

    override fun firstPage() = loadArtifactPage(currentQuery, 0, currentSearchMode)

    override fun previousPage() = loadArtifactPage(
        currentQuery,
        (currentStart - PAGE_SIZE).coerceAtLeast(0),
        currentSearchMode,
    )

    override fun nextPage() = loadArtifactPage(currentQuery, currentStart + PAGE_SIZE, currentSearchMode)

    override fun lastPage() {
        if (totalResults > 0) loadArtifactPage(currentQuery, lastPageStart(), currentSearchMode)
    }

    override fun goToPage() {
        val totalPages = totalPages()
        val requestedPage = view.requestedPage
        if (requestedPage == null || totalPages == 0) {
            view.showInvalidPage(currentPage(), totalPages)
            return
        }
        val targetPage = requestedPage.coerceIn(1, totalPages)
        if (targetPage == currentPage()) {
            view.refreshPagination(currentPage(), totalPages)
            return
        }
        loadArtifactPage(currentQuery, (targetPage - 1) * PAGE_SIZE, currentSearchMode)
    }

    override fun selectArtifact(artifact: MavenArtifact) {
        val sequence = versionRequestSequence.get()
        view.showVersionLoading(artifact)
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching { MavenCentralClient.findVersions(artifact.groupId, artifact.artifactId) }
                .onSuccess { versions -> onUiThread {
                    if (sequence != versionRequestSequence.get()) return@onUiThread
                    view.showVersions(artifact, versions, totalPages())
                } }
                .onFailure { error -> onUiThread {
                    if (sequence != versionRequestSequence.get()) return@onUiThread
                    view.showVersionError(artifact, error.message ?: error.javaClass.simpleName)
                } }
        }
    }

    override fun updatePreview() {
        val artifact = view.selectedArtifact ?: return
        val version = view.selectedVersion?.value ?: return
        val text = when (view.selectedBuildTool) {
            BuildTool.MAVEN -> DependencySnippet.maven(
                artifact.groupId,
                artifact.artifactId,
                version,
                view.selectedScope,
            )
            BuildTool.GRADLE -> DependencySnippet.gradle(
                artifact.groupId,
                artifact.artifactId,
                version,
                view.selectedScope,
                view.selectedFormat,
            )
            BuildTool.SBT -> DependencySnippet.sbt(
                artifact.groupId,
                artifact.artifactId,
                version,
                view.selectedScope,
            )
            BuildTool.MILL -> DependencySnippet.mill(artifact.groupId, artifact.artifactId, version)
            BuildTool.IVY -> DependencySnippet.ivy(artifact.groupId, artifact.artifactId, version)
            BuildTool.GRAPE -> DependencySnippet.grape(artifact.groupId, artifact.artifactId, version)
            BuildTool.LEININGEN -> DependencySnippet.leiningen(artifact.groupId, artifact.artifactId, version)
            BuildTool.BUILDR -> DependencySnippet.buildr(artifact.groupId, artifact.artifactId, version)
        }
        view.renderPreview(text)
    }

    override fun copyPreview() {
        val text = view.previewText.takeIf(String::isNotBlank) ?: return
        view.selectPreview()
        copyText(text, message("status.copied"))
    }

    override fun copyArtifactName(artifact: MavenArtifact) =
        copyText(artifact.artifactId, message("status.copiedArtifactName"))

    override fun copyGroupId(artifact: MavenArtifact) =
        copyText(artifact.groupId, message("status.copiedGroupId"))

    override fun openSettings() {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, MavenDependencyConfigurable::class.java)
        if (!MavenDependencySettings.autoPreload) stopPreload()
        if (!MavenDependencySettings.rememberQuery) {
            PropertiesComponent.getInstance(project).unsetValue(LAST_QUERY_KEY)
        }
    }

    private fun copyText(text: String, statusMessage: String) {
        CopyPasteManager.getInstance().setContents(StringSelection(text))
        view.showStatus(statusMessage)
    }

    private fun loadArtifactPage(query: String, start: Int, mode: SearchMode) {
        val sequence = searchRequestSequence.incrementAndGet()
        versionRequestSequence.incrementAndGet()
        activeSearchIndicator?.cancel()
        view.showSearchBusy()
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            message("search.progress.title"),
            false,
        ) {
            override fun run(indicator: ProgressIndicator) {
                if (sequence != searchRequestSequence.get()) return
                activeSearchIndicator = indicator
                indicator.isIndeterminate = true
                indicator.text2 = query
                try {
                    runCatching { MavenCentralClient.searchArtifacts(query, start, PAGE_SIZE, mode) }
                        .onSuccess { page -> onUiThread {
                            if (sequence != searchRequestSequence.get()) return@onUiThread
                            currentStart = page.start
                            totalResults = page.total
                            view.showSearchResults(page, currentPage(), totalPages())
                            if (MavenDependencySettings.autoPreload) {
                                prefetchRemainingPages(query, page, mode)
                            }
                        } }
                        .onFailure { error -> onUiThread {
                            if (sequence != searchRequestSequence.get()) return@onUiThread
                            view.showSearchError(
                                error.message ?: error.javaClass.simpleName,
                                currentPage(),
                                totalPages(),
                            )
                        } }
                } finally {
                    if (activeSearchIndicator === indicator) activeSearchIndicator = null
                }
            }
        })
    }

    private fun prefetchRemainingPages(query: String, page: ArtifactSearchPage, mode: SearchMode) {
        val nextStart = page.start + PAGE_SIZE
        if (nextStart >= page.total) return
        val key = PreloadKey(query, mode)
        if (activePreload == key) return
        val totalPages = (page.total + PAGE_SIZE - 1) / PAGE_SIZE
        val cachedPages = MavenCentralClient.cachedSearchPageCount(query, PAGE_SIZE, mode, page.total)
        if (cachedPages >= totalPages) return
        activePreload = key
        val generation = preloadGeneration.get()

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            message("preload.title"),
            true,
        ) {
            override fun run(indicator: ProgressIndicator) {
                if (generation != preloadGeneration.get()) return
                activePreloadIndicator = indicator
                indicator.isIndeterminate = false
                updatePreloadIndicator(indicator, query, mode, page.total, totalPages)
                try {
                    var start = nextStart
                    while (
                        start < page.total &&
                        generation == preloadGeneration.get() &&
                        !indicator.isCanceled
                    ) {
                        indicator.text2 = message("preload.loadingPage", start / PAGE_SIZE + 1, totalPages)
                        if (!preloadPage(query, start, mode, generation, indicator)) break
                        updatePreloadIndicator(indicator, query, mode, page.total, totalPages)
                        start += PAGE_SIZE
                        if (start < page.total) Thread.sleep(PRELOAD_PAGE_INTERVAL_MS)
                    }
                } finally {
                    if (activePreloadIndicator === indicator) activePreloadIndicator = null
                    onUiThread {
                        if (generation == preloadGeneration.get() && activePreload == key) {
                            activePreload = null
                        }
                    }
                }
            }
        })
    }

    private fun preloadPage(
        query: String,
        start: Int,
        mode: SearchMode,
        generation: Int,
        indicator: ProgressIndicator,
    ): Boolean {
        repeat(PRELOAD_MAX_ATTEMPTS) { attempt ->
            if (generation != preloadGeneration.get() || indicator.isCanceled) return false
            if (runCatching {
                    MavenCentralClient.searchArtifacts(query, start, PAGE_SIZE, mode)
                }.isSuccess
            ) return true
            if (attempt < PRELOAD_MAX_ATTEMPTS - 1) {
                Thread.sleep(PRELOAD_RETRY_DELAY_MS * (attempt + 1))
            }
        }
        return false
    }

    private fun updatePreloadIndicator(
        indicator: ProgressIndicator,
        query: String,
        mode: SearchMode,
        totalResults: Int,
        totalPages: Int,
    ) {
        val cachedPages = MavenCentralClient.cachedSearchPageCount(query, PAGE_SIZE, mode, totalResults)
        indicator.fraction = cachedPages.toDouble() / totalPages.coerceAtLeast(1)
        indicator.text2 = message("preload.loaded", cachedPages, totalPages)
    }

    private fun currentPage() = if (totalPages() == 0) 0 else currentStart / PAGE_SIZE + 1

    private fun stopPreload() {
        preloadGeneration.incrementAndGet()
        activePreloadIndicator?.cancel()
        activePreload = null
    }

    private fun totalPages() = if (totalResults == 0) 0 else (totalResults + PAGE_SIZE - 1) / PAGE_SIZE

    private fun lastPageStart() = ((totalResults - 1) / PAGE_SIZE) * PAGE_SIZE

    private fun onUiThread(action: () -> Unit) = ApplicationManager.getApplication().invokeLater(action)

    private data class PreloadKey(val query: String, val mode: SearchMode)

    companion object {
        private const val LAST_QUERY_KEY = "maven.dependency.helper.lastQuery"
        private const val PAGE_SIZE = 20
        private const val PRELOAD_MAX_ATTEMPTS = 10
        private const val PRELOAD_RETRY_DELAY_MS = 500L
        private const val PRELOAD_PAGE_INTERVAL_MS = 150L
    }
}
