package io.gitlab.arturbosch.detekt.idea.gradle

import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.Key
import com.intellij.openapi.externalSystem.model.project.ModuleData
import com.intellij.openapi.externalSystem.model.project.ProjectData
import com.intellij.openapi.externalSystem.service.project.IdeModifiableModelsProvider
import com.intellij.openapi.externalSystem.service.project.ProjectDataManager
import com.intellij.openapi.externalSystem.service.project.manage.AbstractProjectDataService
import com.intellij.openapi.externalSystem.service.project.manage.ProjectDataImportListener
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import io.gitlab.arturbosch.detekt.idea.DetektSources
import io.gitlab.arturbosch.detekt.idea.gradle.tooling.DetektSourcesModel
import org.gradle.tooling.model.idea.IdeaModule
import org.jetbrains.plugins.gradle.service.project.AbstractProjectResolverExtension
import org.jetbrains.plugins.gradle.util.GradleConstants

class DetektSourcesData(var directories: List<String> = emptyList())

val DETEKT_SOURCES_KEY: Key<DetektSourcesData> = Key.create(DetektSourcesData::class.java, 1000)

class DetektGradleResolver : AbstractProjectResolverExtension() {

    override fun getExtraProjectModelClasses(): Set<Class<*>> = setOf(DetektSourcesModel::class.java)

    override fun getToolingExtensionsClasses(): Set<Class<*>> = setOf(DetektSourcesModel::class.java)

    override fun populateModuleExtraModels(gradleModule: IdeaModule, ideModule: DataNode<ModuleData>) {
        resolverCtx.getExtraProject(gradleModule, DetektSourcesModel::class.java)?.let {
            ideModule.createChild(DETEKT_SOURCES_KEY, DetektSourcesData(it.directories.toList()))
        }
        super.populateModuleExtraModels(gradleModule, ideModule)
    }
}

class DetektSourcesDataService : AbstractProjectDataService<DetektSourcesData, Void>() {

    override fun getTargetDataKey(): Key<DetektSourcesData> = DETEKT_SOURCES_KEY

    override fun importData(
        toImport: Collection<DataNode<DetektSourcesData>>,
        projectData: ProjectData?,
        project: Project,
        modelsProvider: IdeModifiableModelsProvider,
    ) {
        DetektSources.getInstance(project).update(toImport.flatMap { it.data.directories })
    }
}

class DetektGradleStartup : ProjectActivity {

    override suspend fun execute(project: Project) {
        refreshDetektSources(project)
        project.messageBus.connect(DetektSources.getInstance(project)).subscribe(
            ProjectDataImportListener.TOPIC,
            object : ProjectDataImportListener {
                override fun onImportFinished(projectPath: String?) {
                    refreshDetektSources(project)
                }
            },
        )
    }
}

private fun refreshDetektSources(project: Project) {
    val directories = ProjectDataManager.getInstance()
        .getExternalProjectsData(project, GradleConstants.SYSTEM_ID)
        .mapNotNull { it.externalProjectStructure }
        .flatMap { ExternalSystemApiUtil.findAllRecursively(it, DETEKT_SOURCES_KEY) }
        .flatMap { it.data.directories }
    DetektSources.getInstance(project).update(directories)
}
