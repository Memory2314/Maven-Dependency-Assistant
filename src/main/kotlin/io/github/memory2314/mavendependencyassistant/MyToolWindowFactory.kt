package io.github.memory2314.mavendependencyassistant

import io.github.memory2314.mavendependencyassistant.controller.MavenDependencySearchController
import io.github.memory2314.mavendependencyassistant.ui.MavenDependencySearchView
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class MyToolWindowFactory : ToolWindowFactory {
    override fun shouldBeAvailable(project: Project) = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val view = MavenDependencySearchView(project)
        MavenDependencySearchController(project, view)
        val content = ContentFactory.getInstance().createContent(view.component, null, false)
        content.preferredFocusableComponent = view.searchField
        toolWindow.contentManager.addContent(content)
    }
}
