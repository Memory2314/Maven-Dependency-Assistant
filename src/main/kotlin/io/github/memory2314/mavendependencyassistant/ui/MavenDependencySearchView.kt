package io.github.memory2314.mavendependencyassistant.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.*
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import io.github.memory2314.mavendependencyassistant.MyMessageBundle.message
import io.github.memory2314.mavendependencyassistant.maven.*
import java.awt.*
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeWillExpandListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

internal interface MavenDependencySearchCallbacks {
    fun search()
    fun firstPage()
    fun previousPage()
    fun goToPage()
    fun nextPage()
    fun lastPage()
    fun selectArtifact(artifact: MavenArtifact)
    fun updatePreview()
    fun copyPreview()
    fun copyArtifactName(artifact: MavenArtifact)
    fun copyGroupId(artifact: MavenArtifact)
    fun openSettings()
}

internal class MavenDependencySearchView(private val project: Project) {
    private var callbacks: MavenDependencySearchCallbacks? = null
    private var displayedSearchMode = SearchMode.JAR
    private var previewAutoCopyArmed = false
    private var groupByGroupId = false
    private val currentArtifacts = mutableListOf<MavenArtifact>()
    private val versionsByArtifact = mutableMapOf<MavenArtifact, List<MavenVersion>>()
    private val loadedArtifacts = mutableSetOf<MavenArtifact>()
    private val loadingArtifacts = mutableSetOf<MavenArtifact>()
    private val artifactNodes = mutableMapOf<MavenArtifact, DefaultMutableTreeNode>()

    val searchField = JBTextField().apply {
        emptyText.text = message("search.placeholder.jar")
    }
    private val searchButton = JButton(message("search.button"))
    private val searchModeButton = JToggleButton(AllIcons.Nodes.PpJar).apply {
        val side = searchButton.preferredSize.height
        val size = Dimension(side, side)
        preferredSize = size
        minimumSize = size
        maximumSize = size
        margin = Insets(0, 0, 0, 0)
        isFocusable = false
        toolTipText = message("search.mode.jar.tooltip")
        accessibleContext.accessibleName = message("search.mode.jar.accessible")
    }
    private val groupByButton = JToggleButton(AllIcons.Actions.GroupByPackage).apply {
        val side = searchButton.preferredSize.height
        val size = Dimension(side, side)
        preferredSize = size
        minimumSize = size
        maximumSize = size
        margin = Insets(0, 0, 0, 0)
        isFocusable = false
        toolTipText = message("group.enable.tooltip")
        accessibleContext.accessibleName = toolTipText
    }
    private val settingsButton = JButton(AllIcons.General.GearPlain).apply {
        val side = searchButton.preferredSize.height
        val size = Dimension(side, side)
        preferredSize = size
        minimumSize = size
        maximumSize = size
        margin = Insets(0, 0, 0, 0)
        isFocusable = false
        toolTipText = message("settings.tooltip")
        accessibleContext.accessibleName = toolTipText
    }
    private val dependencyRoot = DefaultMutableTreeNode()
    private val dependencyTreeModel = DefaultTreeModel(dependencyRoot)
    private val dependencyTree = Tree(dependencyTreeModel).apply {
        isRootVisible = false
        showsRootHandles = true
        toggleClickCount = 1
        emptyText.clear().appendLine(
            AllIcons.Actions.Search,
            message("results.empty.initial"),
            SimpleTextAttributes.GRAYED_ATTRIBUTES,
            null,
        )
        cellRenderer = object : ColoredTreeCellRenderer() {
            override fun customizeCellRenderer(
                tree: JTree,
                value: Any,
                selected: Boolean,
                expanded: Boolean,
                leaf: Boolean,
                row: Int,
                hasFocus: Boolean,
            ) {
                border = JBUI.Borders.empty(5, 4)
                val node = value as? DefaultMutableTreeNode ?: return
                when (val item = node.userObject) {
                    is ArtifactGroup -> {
                        icon = AllIcons.Nodes.Package
                        append(item.groupId, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                        toolTipText = message("group.tooltip", item.groupId)
                    }
                    is MavenArtifact -> {
                        icon = if (displayedSearchMode == SearchMode.CLASS) AllIcons.Nodes.Class else AllIcons.Nodes.PpJar
                        append(item.artifactId, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                        append("   ${item.groupId}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        toolTipText = message("artifact.tooltip", item.artifactId, item.groupId)
                    }
                    is MavenVersion -> {
                        icon = AllIcons.Nodes.Tag
                        append(item.value, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                        item.date?.takeIf(String::isNotBlank)?.let {
                            append("   $it", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        }
                        toolTipText = item.value
                    }
                    LoadingVersions -> {
                        append(message("versions.loading"), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        toolTipText = null
                    }
                }
            }
        }
    }
    private val pageField = JBTextField("0", 4).apply {
        horizontalAlignment = SwingConstants.CENTER
        isEnabled = false
        toolTipText = message("page.input.tooltip")
        maximumSize = preferredSize
    }
    private val firstPageButton = paginationButton(AllIcons.Actions.Play_first, message("pagination.first"))
    private val previousPageButton = paginationButton(AllIcons.Actions.Play_back, message("pagination.previous"))
    private val nextPageButton = paginationButton(AllIcons.Actions.Play_forward, message("pagination.next"))
    private val lastPageButton = paginationButton(AllIcons.Actions.Play_last, message("pagination.last"))
    private val scopeCombo = ComboBox(arrayOf("Compile", "Test", "Runtime", "Provided"))
    private val buildToolCombo = ComboBox(BuildTool.entries.toTypedArray())
    private val formatLabel = JBLabel(message("label.format")).apply { isVisible = false }
    private val formatCombo = ComboBox(GradleFormat.entries.toTypedArray()).apply { isVisible = false }
    private val dependencyOptions = createDependencyOptions()
    private val preview = EditorTextField("", project, previewFileType()).apply {
        setDisposedWith(project)
        setViewer(true)
        setOneLineMode(false)
        preferredSize = Dimension(JBUI.scale(420), JBUI.scale(160))
        toolTipText = message("preview.tooltip")
        addSettingsProvider { editor ->
            editor.settings.apply {
                isUseSoftWraps = true
                isLineNumbersShown = false
                isFoldingOutlineShown = false
                isRightMarginShown = false
                isCaretRowShown = false
                additionalLinesCount = 0
                additionalColumnsCount = 0
            }
        }
    }
    private val status = JBLabel(message("status.initial"))

    val component: JComponent = JBPanel<JBPanel<*>>(BorderLayout(JBUI.scale(8), JBUI.scale(8))).apply {
        border = JBUI.Borders.empty(10)
        add(JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            add(searchField, BorderLayout.CENTER)
            add(JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                add(searchModeButton)
                add(Box.createHorizontalStrut(JBUI.scale(4)))
                add(groupByButton)
                add(Box.createHorizontalStrut(JBUI.scale(4)))
                add(settingsButton)
                add(Box.createHorizontalStrut(JBUI.scale(6)))
                add(searchButton)
            }, BorderLayout.EAST)
        }, BorderLayout.NORTH)
        add(OnePixelSplitter(true, "maven.dependency.helper.main.splitter", 0.7f).apply {
            firstComponent = JPanel(BorderLayout()).apply {
                add(JBScrollPane(dependencyTree).apply {
                    preferredSize = Dimension(JBUI.scale(420), JBUI.scale(260))
                }, BorderLayout.CENTER)
                add(createPaginationPanel(), BorderLayout.SOUTH)
            }
            secondComponent = FormBuilder.createFormBuilder()
                .addComponent(dependencyOptions)
                .addComponent(TitledSeparator(message("preview.title")))
                .addComponentFillVertically(preview, 0)
                .panel.apply {
                    border = JBUI.Borders.emptyTop(8)
                }
        }, BorderLayout.CENTER)
        add(status, BorderLayout.SOUTH)
    }

    init {
        searchButton.addActionListener { callbacks?.search() }
        searchField.addActionListener { callbacks?.search() }
        searchModeButton.addActionListener {
            val classMode = searchModeButton.isSelected
            searchModeButton.icon = if (classMode) AllIcons.Nodes.Class else AllIcons.Nodes.PpJar
            searchModeButton.toolTipText = if (classMode) {
                message("search.mode.class.tooltip")
            } else {
                message("search.mode.jar.tooltip")
            }
            searchModeButton.accessibleContext.accessibleName = if (classMode) {
                message("search.mode.class.accessible")
            } else {
                message("search.mode.jar.accessible")
            }
            searchField.emptyText.text = if (classMode) {
                message("search.placeholder.class")
            } else {
                message("search.placeholder.jar")
            }
        }
        groupByButton.addActionListener {
            groupByGroupId = groupByButton.isSelected
            groupByButton.toolTipText = message(
                if (groupByGroupId) "group.disable.tooltip" else "group.enable.tooltip",
            )
            groupByButton.accessibleContext.accessibleName = groupByButton.toolTipText
            rebuildDependencyTree()
        }
        settingsButton.addActionListener {
            SwingUtilities.invokeLater { callbacks?.openSettings() }
        }
        firstPageButton.addActionListener { callbacks?.firstPage() }
        previousPageButton.addActionListener { callbacks?.previousPage() }
        pageField.addActionListener { callbacks?.goToPage() }
        pageField.addFocusListener(object : FocusAdapter() {
            override fun focusGained(event: FocusEvent) = pageField.selectAll()
        })
        nextPageButton.addActionListener { callbacks?.nextPage() }
        lastPageButton.addActionListener { callbacks?.lastPage() }
        dependencyTree.addTreeWillExpandListener(object : TreeWillExpandListener {
            override fun treeWillExpand(event: TreeExpansionEvent) {
                val artifact = (event.path.lastPathComponent as? DefaultMutableTreeNode)
                    ?.userObject as? MavenArtifact ?: return
                if (artifact !in loadedArtifacts && loadingArtifacts.add(artifact)) {
                    callbacks?.selectArtifact(artifact)
                }
            }

            override fun treeWillCollapse(event: TreeExpansionEvent) = Unit
        })
        dependencyTree.addTreeSelectionListener {
            if (selectedVersion != null) callbacks?.updatePreview() else clearPreview()
        }
        dependencyTree.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) = showArtifactPopup(event)
            override fun mouseReleased(event: MouseEvent) = showArtifactPopup(event)
        })
        scopeCombo.addActionListener { callbacks?.updatePreview() }
        buildToolCombo.addActionListener {
            updateBuildToolOptions()
            callbacks?.updatePreview()
        }
        formatCombo.addActionListener { callbacks?.updatePreview() }
        preview.addMouseListener(object : MouseAdapter() {
            override fun mouseReleased(event: MouseEvent) {
                val hasSelection = preview.getEditor(false)?.selectionModel?.hasSelection() == true
                if (!hasSelection && previewAutoCopyArmed) {
                    previewAutoCopyArmed = false
                    callbacks?.copyPreview()
                }
            }
        })
    }

    val searchQuery: String get() = searchField.text.trim()
    val searchMode: SearchMode get() = if (searchModeButton.isSelected) SearchMode.CLASS else SearchMode.JAR
    val requestedPage: Int? get() = pageField.text.trim().toIntOrNull()
    val selectedArtifact: MavenArtifact?
        get() {
            val node = dependencyTree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return null
            return when (val item = node.userObject) {
                is MavenArtifact -> item
                is MavenVersion -> (node.parent as? DefaultMutableTreeNode)?.userObject as? MavenArtifact
                else -> null
            }
        }
    val selectedVersion: MavenVersion?
        get() = (dependencyTree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? MavenVersion
    val selectedScope: String get() = (scopeCombo.selectedItem as String).lowercase()
    val selectedBuildTool: BuildTool get() = buildToolCombo.selectedItem as BuildTool
    val selectedFormat: GradleFormat get() = formatCombo.selectedItem as GradleFormat
    val previewText: String get() = preview.text

    fun bind(callbacks: MavenDependencySearchCallbacks) {
        this.callbacks = callbacks
    }

    fun restoreQuery(query: String) {
        searchField.text = query
    }

    fun requestSearchFocus() = searchField.requestFocusInWindow()

    fun showStatus(message: String) {
        status.text = message
    }

    fun showSearchBusy() {
        displayedSearchMode = searchMode
        showListEmptyState(AllIcons.Actions.Search, message("status.searching"))
        searchButton.isEnabled = false
        setPaginationEnabled(false, false, false)
        clearDependencyTree()
        clearPreview()
        showStatus(message("status.searching"))
    }

    fun showSearchResults(page: ArtifactSearchPage, currentPage: Int, totalPages: Int) {
        searchButton.isEnabled = true
        clearDependencyTree()
        currentArtifacts.addAll(page.artifacts)
        rebuildDependencyTree()
        showListEmptyState(AllIcons.Actions.Search, message("results.empty"))
        updatePagination(currentPage, totalPages)
        showStatus(if (page.artifacts.isEmpty()) message("results.empty") else message("results.count", page.total))
    }

    fun showSearchError(errorMessage: String, currentPage: Int, totalPages: Int) {
        searchButton.isEnabled = true
        updatePagination(currentPage, totalPages)
        showListEmptyState(
            AllIcons.General.Error,
            message("results.searchFailed"),
            SimpleTextAttributes.ERROR_ATTRIBUTES,
        )
        showStatus(message("status.requestFailed", errorMessage))
    }

    fun showVersionLoading(artifact: MavenArtifact) {
        showStatus(message("status.loadingVersions", artifact.artifactId))
        clearPreview()
    }

    fun showVersions(artifact: MavenArtifact, versions: List<MavenVersion>, totalPages: Int) {
        val artifactNode = artifactNodes[artifact] ?: return
        loadingArtifacts.remove(artifact)
        loadedArtifacts.add(artifact)
        versionsByArtifact[artifact] = versions
        artifactNode.removeAllChildren()
        versions.forEach { artifactNode.add(DefaultMutableTreeNode(it, false)) }
        dependencyTreeModel.nodeStructureChanged(artifactNode)
        val artifactPath = TreePath(artifactNode.path)
        dependencyTree.expandPath(artifactPath)
        showStatus(if (versions.isEmpty()) {
            message("status.pagesNoVersions", totalPages)
        } else {
            message("status.pages", totalPages)
        })
    }

    fun showVersionError(artifact: MavenArtifact, errorMessage: String) {
        loadingArtifacts.remove(artifact)
        artifactNodes[artifact]?.let { node ->
            if (node.childCount == 0) node.add(DefaultMutableTreeNode(LoadingVersions, false))
            dependencyTreeModel.nodeStructureChanged(node)
            dependencyTree.collapsePath(TreePath(node.path))
        }
        showStatus(message("status.versionRequestFailed", errorMessage))
    }

    fun showInvalidPage(currentPage: Int, totalPages: Int) {
        updatePagination(currentPage, totalPages)
        showStatus(message("status.invalidPage"))
    }

    fun refreshPagination(currentPage: Int, totalPages: Int) {
        updatePagination(currentPage, totalPages)
    }

    fun renderPreview(text: String) {
        if (preview.text != text) previewAutoCopyArmed = true
        preview.setFileType(previewFileType())
        preview.text = text
        preview.setCaretPosition(0)
    }

    fun selectPreview() {
        preview.requestFocusInWindow()
        preview.selectAll()
    }

    private fun updatePagination(currentPage: Int, totalPages: Int) {
        pageField.text = currentPage.toString()
        pageField.isEnabled = totalPages > 0
        setPaginationEnabled(
            canGoBack = currentPage > 1,
            canGoForward = currentPage in 1 until totalPages,
            pageEnabled = totalPages > 0,
        )
    }

    private fun setPaginationEnabled(canGoBack: Boolean, canGoForward: Boolean, pageEnabled: Boolean) {
        firstPageButton.isEnabled = canGoBack
        previousPageButton.isEnabled = canGoBack
        pageField.isEnabled = pageEnabled
        nextPageButton.isEnabled = canGoForward
        lastPageButton.isEnabled = canGoForward
    }

    private fun showListEmptyState(
        icon: Icon,
        text: String,
        attributes: SimpleTextAttributes = SimpleTextAttributes.GRAYED_ATTRIBUTES,
    ) {
        dependencyTree.emptyText.clear().appendLine(icon, text, attributes, null)
    }

    private fun clearDependencyTree() {
        currentArtifacts.clear()
        versionsByArtifact.clear()
        loadedArtifacts.clear()
        loadingArtifacts.clear()
        artifactNodes.clear()
        dependencyRoot.removeAllChildren()
        dependencyTreeModel.reload()
    }

    private fun rebuildDependencyTree() {
        val expandedArtifacts = artifactNodes.filterValues { node ->
            dependencyTree.isExpanded(TreePath(node.path))
        }.keys
        artifactNodes.clear()
        dependencyRoot.removeAllChildren()

        fun artifactNode(artifact: MavenArtifact) = DefaultMutableTreeNode(artifact).also { node ->
            val versions = versionsByArtifact[artifact]
            if (versions == null) {
                node.add(DefaultMutableTreeNode(LoadingVersions, false))
            } else {
                versions.forEach { node.add(DefaultMutableTreeNode(it, false)) }
            }
            artifactNodes[artifact] = node
        }

        if (groupByGroupId) {
            currentArtifacts.groupBy(MavenArtifact::groupId).forEach { (groupId, artifacts) ->
                dependencyRoot.add(DefaultMutableTreeNode(ArtifactGroup(groupId)).apply {
                    artifacts.forEach { add(artifactNode(it)) }
                })
            }
        } else {
            currentArtifacts.forEach { dependencyRoot.add(artifactNode(it)) }
        }
        dependencyTreeModel.reload()

        if (groupByGroupId) {
            repeat(dependencyRoot.childCount) { index ->
                dependencyTree.expandPath(TreePath((dependencyRoot.getChildAt(index) as DefaultMutableTreeNode).path))
            }
        }
        expandedArtifacts.forEach { artifact ->
            artifactNodes[artifact]?.let { dependencyTree.expandPath(TreePath(it.path)) }
        }
    }

    private fun showArtifactPopup(event: MouseEvent) {
        if (!event.isPopupTrigger) return
        val node = dependencyTree.getPathForLocation(event.x, event.y)?.lastPathComponent
            as? DefaultMutableTreeNode ?: return
        val artifact = node.userObject as? MavenArtifact ?: return
        val actions = DefaultActionGroup().apply {
            add(object : AnAction(message("artifact.copyName"), null, AllIcons.Actions.Copy) {
                override fun actionPerformed(actionEvent: AnActionEvent) {
                    callbacks?.copyArtifactName(artifact)
                }
            })
            add(object : AnAction(message("artifact.copyGroup"), null, AllIcons.Actions.Copy) {
                override fun actionPerformed(actionEvent: AnActionEvent) {
                    callbacks?.copyGroupId(artifact)
                }
            })
        }
        ActionManager.getInstance()
            .createActionPopupMenu("MavenDependencyHelper.ArtifactPopup", actions)
            .component
            .show(dependencyTree, event.x, event.y)
    }

    private fun clearPreview() {
        previewAutoCopyArmed = false
        preview.text = ""
    }

    private fun setFormatVisible(visible: Boolean) {
        formatLabel.isVisible = visible
        formatCombo.isVisible = visible
        dependencyOptions.revalidate()
        dependencyOptions.repaint()
    }

    private fun updateBuildToolOptions() {
        val tool = selectedBuildTool
        if (tool == BuildTool.MILL) scopeCombo.selectedItem = "Compile"
        scopeCombo.isEnabled = tool != BuildTool.MILL
        setFormatVisible(tool == BuildTool.GRADLE)
    }

    private fun paginationButton(icon: Icon, tooltip: String) = JButton(icon).apply {
        val side = pageField.preferredSize.height
        val size = Dimension(side, side)
        preferredSize = size
        minimumSize = size
        maximumSize = size
        margin = Insets(0, 0, 0, 0)
        isBorderPainted = false
        isContentAreaFilled = false
        isOpaque = false
        isFocusable = false
        isEnabled = false
        toolTipText = tooltip
        accessibleContext.accessibleName = tooltip
        model.addChangeListener {
            isContentAreaFilled = model.isRollover || model.isPressed
        }
    }

    private fun previewFileType() = FileTypeManager.getInstance().getFileTypeByExtension(
        when (buildToolCombo.selectedItem as? BuildTool) {
            BuildTool.MAVEN, null -> "xml"
            BuildTool.GRADLE -> if (formatCombo.selectedItem == GradleFormat.KOTLIN) "kts" else "gradle"
            BuildTool.SBT -> "sbt"
            BuildTool.MILL -> "mill"
            BuildTool.IVY -> "xml"
            BuildTool.GRAPE -> "groovy"
            BuildTool.LEININGEN -> "clj"
            BuildTool.BUILDR -> "rb"
        },
    )

    private fun createPaginationPanel() = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        border = JBUI.Borders.empty(3, 0)
        add(Box.createHorizontalGlue())
        add(firstPageButton)
        add(Box.createHorizontalStrut(JBUI.scale(3)))
        add(previousPageButton)
        add(Box.createHorizontalStrut(JBUI.scale(4)))
        add(pageField)
        add(Box.createHorizontalStrut(JBUI.scale(4)))
        add(nextPageButton)
        add(Box.createHorizontalStrut(JBUI.scale(3)))
        add(lastPageButton)
        add(Box.createHorizontalGlue())
    }

    private fun createDependencyOptions() = JPanel(GridBagLayout()).apply {
        val gap = JBUI.scale(8)
        fun label(text: String, row: Int) = add(JBLabel(text), GridBagConstraints().apply {
            gridx = 0
            gridy = row
            anchor = GridBagConstraints.LINE_END
            insets = Insets(0, 0, if (row == 0) JBUI.scale(4) else 0, gap)
        })

        label(message("label.build"), 0)
        add(buildToolCombo, GridBagConstraints().apply {
            gridx = 1
            gridy = 0
            anchor = GridBagConstraints.LINE_START
            insets = Insets(0, 0, JBUI.scale(4), 0)
        })
        label(message("label.scope"), 1)
        add(scopeCombo, GridBagConstraints().apply {
            gridx = 1
            gridy = 1
            anchor = GridBagConstraints.LINE_START
        })
        add(formatLabel, GridBagConstraints().apply {
            gridx = 2
            gridy = 1
            anchor = GridBagConstraints.LINE_END
            insets = Insets(0, JBUI.scale(12), 0, gap)
        })
        add(formatCombo, GridBagConstraints().apply {
            gridx = 3
            gridy = 1
            anchor = GridBagConstraints.LINE_START
        })
        add(Box.createHorizontalGlue(), GridBagConstraints().apply {
            gridx = 4
            gridy = 0
            gridheight = 2
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
        })
    }

    private data class ArtifactGroup(val groupId: String)
    private object LoadingVersions

}
