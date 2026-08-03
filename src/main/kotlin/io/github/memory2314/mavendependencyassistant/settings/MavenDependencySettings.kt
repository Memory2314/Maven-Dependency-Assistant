package io.github.memory2314.mavendependencyassistant.settings

import com.intellij.ide.util.PropertiesComponent

internal object MavenDependencySettings {
    const val DEFAULT_SEARCH_URL = "https://central.sonatype.com/solrsearch/select"
    const val DEFAULT_REPOSITORY_URL = "https://repo.maven.apache.org/maven2"

    private val properties: PropertiesComponent
        get() = PropertiesComponent.getInstance()

    var searchUrl: String
        get() = properties.getValue(SEARCH_URL_KEY, DEFAULT_SEARCH_URL)
        set(value) = properties.setValue(SEARCH_URL_KEY, value, DEFAULT_SEARCH_URL)

    var repositoryUrl: String
        get() = properties.getValue(REPOSITORY_URL_KEY, DEFAULT_REPOSITORY_URL)
        set(value) = properties.setValue(REPOSITORY_URL_KEY, value, DEFAULT_REPOSITORY_URL)

    var autoPreload: Boolean
        get() = properties.getBoolean(AUTO_PRELOAD_KEY, true)
        set(value) = properties.setValue(AUTO_PRELOAD_KEY, value, true)

    var rememberQuery: Boolean
        get() = properties.getBoolean(REMEMBER_QUERY_KEY, true)
        set(value) = properties.setValue(REMEMBER_QUERY_KEY, value, true)

    fun reset() {
        properties.unsetValue(SEARCH_URL_KEY)
        properties.unsetValue(REPOSITORY_URL_KEY)
        properties.unsetValue(AUTO_PRELOAD_KEY)
        properties.unsetValue(REMEMBER_QUERY_KEY)
    }

    private const val SEARCH_URL_KEY = "maven.dependency.helper.searchUrl"
    private const val REPOSITORY_URL_KEY = "maven.dependency.helper.repositoryUrl"
    private const val AUTO_PRELOAD_KEY = "maven.dependency.helper.autoPreload"
    private const val REMEMBER_QUERY_KEY = "maven.dependency.helper.rememberQuery"
}
