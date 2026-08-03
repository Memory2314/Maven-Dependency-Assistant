package io.github.memory2314.mavendependencyassistant.settings

import io.github.memory2314.mavendependencyassistant.MyMessageBundle.message
import io.github.memory2314.mavendependencyassistant.maven.MavenCentralClient
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.net.URI
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

class MavenDependencyConfigurable : Configurable {
    private var searchUrlField: JBTextField? = null
    private var repositoryUrlField: JBTextField? = null
    private var autoPreloadCheckBox: JBCheckBox? = null
    private var rememberQueryCheckBox: JBCheckBox? = null
    private var statusLabel: JBLabel? = null
    private var panel: JPanel? = null

    override fun getDisplayName() = message("settings.displayName")

    override fun createComponent(): JComponent {
        searchUrlField = JBTextField(50)
        repositoryUrlField = JBTextField(50)
        autoPreloadCheckBox = JBCheckBox(message("settings.autoPreload"))
        rememberQueryCheckBox = JBCheckBox(message("settings.rememberQuery"))
        statusLabel = JBLabel()

        val actions = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
            add(JButton(message("settings.clearCache")).apply {
                addActionListener {
                    MavenCentralClient.clearCaches()
                    NotificationGroupManager.getInstance()
                        .getNotificationGroup(NOTIFICATION_GROUP_ID)
                        .createNotification(message("status.cacheCleared"), NotificationType.INFORMATION)
                        .notify(null)
                }
            })
            add(JButton(message("settings.reset")).apply {
                addActionListener {
                    searchUrlField?.text = MavenDependencySettings.DEFAULT_SEARCH_URL
                    repositoryUrlField?.text = MavenDependencySettings.DEFAULT_REPOSITORY_URL
                    autoPreloadCheckBox?.isSelected = true
                    rememberQueryCheckBox?.isSelected = true
                    statusLabel?.text = ""
                }
            })
        }

        panel = FormBuilder.createFormBuilder()
            .addComponent(JBLabel(message("settings.description")))
            .addVerticalGap(JBUI.scale(8))
            .addComponent(createUrlFieldsPanel())
            .addVerticalGap(JBUI.scale(8))
            .addComponent(leftAligned(autoPreloadCheckBox!!))
            .addComponent(leftAligned(rememberQueryCheckBox!!))
            .addVerticalGap(JBUI.scale(8))
            .addComponent(actions)
            .addComponent(statusLabel!!)
            .addComponentFillVertically(JPanel(), 0)
            .panel
        reset()
        return panel!!
    }

    override fun isModified(): Boolean =
        normalized(searchUrlField?.text) != MavenDependencySettings.searchUrl ||
            normalized(repositoryUrlField?.text) != MavenDependencySettings.repositoryUrl ||
            autoPreloadCheckBox?.isSelected != MavenDependencySettings.autoPreload ||
            rememberQueryCheckBox?.isSelected != MavenDependencySettings.rememberQuery

    @Throws(ConfigurationException::class)
    override fun apply() {
        val searchUrl = validateUrl(searchUrlField?.text)
        val repositoryUrl = validateUrl(repositoryUrlField?.text)
        val endpointChanged = searchUrl != MavenDependencySettings.searchUrl ||
            repositoryUrl != MavenDependencySettings.repositoryUrl
        MavenDependencySettings.searchUrl = searchUrl
        MavenDependencySettings.repositoryUrl = repositoryUrl
        MavenDependencySettings.autoPreload = autoPreloadCheckBox?.isSelected == true
        MavenDependencySettings.rememberQuery = rememberQueryCheckBox?.isSelected == true
        if (endpointChanged) MavenCentralClient.clearCaches()
        statusLabel?.text = message("status.settingsSaved")
    }

    override fun reset() {
        searchUrlField?.text = MavenDependencySettings.searchUrl
        repositoryUrlField?.text = MavenDependencySettings.repositoryUrl
        autoPreloadCheckBox?.isSelected = MavenDependencySettings.autoPreload
        rememberQueryCheckBox?.isSelected = MavenDependencySettings.rememberQuery
        statusLabel?.text = ""
    }

    override fun disposeUIResources() {
        panel = null
        searchUrlField = null
        repositoryUrlField = null
        autoPreloadCheckBox = null
        rememberQueryCheckBox = null
        statusLabel = null
    }

    private fun validateUrl(value: String?): String {
        val normalized = normalized(value)
        val valid = runCatching { URI(normalized) }.getOrNull()?.let {
            it.scheme in setOf("http", "https") && !it.host.isNullOrBlank()
        } == true
        if (!valid) throw ConfigurationException(message("settings.url.invalid"))
        return normalized
    }

    private fun normalized(value: String?) = value.orEmpty().trim().trimEnd('/')

    private fun leftAligned(component: JComponent) = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
        isOpaque = false
        add(component)
    }

    private fun createUrlFieldsPanel() = JPanel(GridBagLayout()).apply {
        isOpaque = false
        fun addRow(label: String, field: JComponent, row: Int) {
            add(JBLabel(label), GridBagConstraints().apply {
                gridx = 0
                gridy = row
                anchor = GridBagConstraints.LINE_END
                insets = Insets(0, 0, if (row == 0) JBUI.scale(6) else 0, JBUI.scale(8))
            })
            add(field, GridBagConstraints().apply {
                gridx = 1
                gridy = row
                weightx = 1.0
                fill = GridBagConstraints.HORIZONTAL
                insets = Insets(0, 0, if (row == 0) JBUI.scale(6) else 0, 0)
            })
        }
        addRow(message("settings.searchUrl"), searchUrlField!!, 0)
        addRow(message("settings.repositoryUrl"), repositoryUrlField!!, 1)
    }

    private companion object {
        const val NOTIFICATION_GROUP_ID = "Maven Dependency Assistant"
    }
}
