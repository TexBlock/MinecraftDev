/*
 * Minecraft Development for IntelliJ
 *
 * https://mcdev.io/
 *
 * Copyright (C) 2024 minecraft-dev
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, version 3.0 only.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.demonwav.mcdev.creator.custom

import com.demonwav.mcdev.MinecraftSettings
import com.demonwav.mcdev.asset.MCDevBundle
import com.demonwav.mcdev.creator.custom.providers.EmptyLoadedTemplate
import com.demonwav.mcdev.creator.custom.providers.LoadedTemplate
import com.demonwav.mcdev.creator.custom.providers.TemplateProvider
import com.demonwav.mcdev.creator.modalityState
import com.intellij.ide.wizard.AbstractNewProjectWizardStep
import com.intellij.ide.wizard.GitNewProjectWizardData
import com.intellij.ide.wizard.NewProjectWizardBaseData
import com.intellij.ide.wizard.NewProjectWizardStep
import com.intellij.openapi.diagnostic.getOrLogException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.observable.properties.GraphProperty
import com.intellij.openapi.observable.util.transform
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.ui.JBColor
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.Placeholder
import com.intellij.ui.dsl.builder.SegmentedButton
import com.intellij.ui.dsl.builder.TopGap
import com.intellij.ui.dsl.builder.bindText
import com.intellij.util.application
import com.intellij.util.ui.AsyncProcessIcon
import javax.swing.JLabel
import kotlin.collections.component1
import kotlin.collections.component2

/**
 * The step to select a custom template repo.
 */
class CustomPlatformStep(
    parent: NewProjectWizardStep,
) : AbstractNewProjectWizardStep(parent) {

    val templateRepos = MinecraftSettings.instance.creatorTemplateRepos

    val templateRepoProperty = propertyGraph.property<MinecraftSettings.TemplateRepo>(
        templateRepos.firstOrNull() ?: MinecraftSettings.TemplateRepo.makeBuiltinRepo()
    )
    var templateRepo by templateRepoProperty

    val availableGroupsProperty = propertyGraph.property<Collection<String>>(emptyList())
    var availableGroups by availableGroupsProperty
    val availableTemplatesProperty = propertyGraph.property<Collection<LoadedTemplate>>(emptyList())
    var availableTemplates by availableTemplatesProperty
    lateinit var availableGroupsSegmentedButton: SegmentedButton<String>
    lateinit var availableTemplatesSegmentedButton: SegmentedButton<LoadedTemplate>

    val selectedGroupProperty = propertyGraph.property<String>("")
    var selectedGroup by selectedGroupProperty
    val selectedTemplateProperty = propertyGraph.property<LoadedTemplate>(EmptyLoadedTemplate)
    var selectedTemplate by selectedTemplateProperty

    val templateProvidersLoadingProperty = propertyGraph.property<Boolean>(true)
    val templateProvidersTextProperty = propertyGraph.property("")
    val templateProvidersText2Property = propertyGraph.property("")
    lateinit var templateProvidersProcessIcon: Cell<AsyncProcessIcon>

    val templateLoadingProperty = propertyGraph.property<Boolean>(true)
    val templateLoadingTextProperty = propertyGraph.property<String>("")
    val templateLoadingText2Property = propertyGraph.property<String>("")
    lateinit var templatePropertiesProcessIcon: Cell<AsyncProcessIcon>
    lateinit var noTemplatesAvailable: Cell<JLabel>
    var templateLoadingIndicator: ProgressIndicator? = null

    private val externalPropertyProvider = object : ExternalTemplatePropertyProvider {
        override val projectNameProperty: GraphProperty<String>
            get() = data.getUserData(NewProjectWizardBaseData.KEY)?.nameProperty
                ?: throw RuntimeException("Could not find wizard base data")

        override val useGit: Boolean
            get() = data.getUserData(GitNewProjectWizardData.KEY)?.git == true
    }
    private val templateProcessor = CreatorTemplateProcessor(propertyGraph, context, externalPropertyProvider)

    override fun setupUI(builder: Panel) {
        lateinit var templatePropertyPlaceholder: Placeholder

        builder.row(MCDevBundle("creator.ui.custom.repos.label")) {
            segmentedButton(templateRepos) { it.name }
                .bind(templateRepoProperty)
        }.visible(templateRepos.size > 1)

        builder.row {
            templateProvidersProcessIcon =
                cell(AsyncProcessIcon("TemplateProviders init"))
                    .visibleIf(templateProvidersLoadingProperty)
            label(MCDevBundle("creator.step.generic.init_template_providers.message"))
                .visibleIf(templateProvidersLoadingProperty)
            label("")
                .bindText(templateProvidersTextProperty)
                .visibleIf(templateProvidersLoadingProperty)
            label("")
                .bindText(templateProvidersText2Property)
                .visibleIf(templateProvidersLoadingProperty)
        }

        templateRepoProperty.afterChange { templateRepo ->
            templatePropertyPlaceholder.component = null
            availableTemplates = emptyList()
            loadTemplatesInBackground {
                val provider = TemplateProvider.get(templateRepo.provider)
                provider?.loadTemplates(context, templateRepo).orEmpty()
            }
        }

        builder.row(MCDevBundle("creator.ui.custom.groups.label")) {
            availableGroupsSegmentedButton =
                segmentedButton(emptyList<String>(), String::toString)
                    .bind(selectedGroupProperty)
        }.visibleIf(
            availableGroupsProperty.transform { it.size > 1 }
        )

        builder.row(MCDevBundle("creator.ui.custom.templates.label")) {
            availableTemplatesSegmentedButton =
                segmentedButton(emptyList(), LoadedTemplate::label, LoadedTemplate::tooltip)
                    .bind(selectedTemplateProperty)
                    .validation {
                        addApplyRule("", condition = templateProcessor::hasTemplateErrors)
                    }
        }.visibleIf(
            availableTemplatesProperty.transform { it.size > 1 }
        )

        availableTemplatesProperty.afterChange { newTemplates ->
            val groups = newTemplates.mapTo(linkedSetOf()) { it.descriptor.translatedGroup }
            availableGroupsSegmentedButton.items(groups)
            // availableGroupsSegmentedButton.visible(groups.size > 1)
            availableGroups = groups
            selectedGroup = groups.firstOrNull() ?: "empty"
        }

        selectedGroupProperty.afterChange { group ->
            val templates = availableTemplates.filter { it.descriptor.translatedGroup == group }
            availableTemplatesSegmentedButton.items(templates)
            // Force visiblity because the component might become hidden and not show up again
            //  when the segmented button switches between dropdown and buttons
            availableTemplatesSegmentedButton.visible(true)
            templatePropertyPlaceholder.component = null
            selectedTemplate = templates.firstOrNull() ?: EmptyLoadedTemplate
        }

        selectedTemplateProperty.afterChange { template ->
            templatePropertyPlaceholder.component = templateProcessor.createOptionsPanel(template)
        }

        builder.row {
            templatePropertiesProcessIcon =
                cell(AsyncProcessIcon("Templates loading"))
                    .visibleIf(templateLoadingProperty)
            label(MCDevBundle("creator.step.generic.load_template.message"))
                .visibleIf(templateLoadingProperty)
            label("")
                .bindText(templateLoadingTextProperty)
                .visibleIf(templateLoadingProperty)
            label("")
                .bindText(templateLoadingText2Property)
                .visibleIf(templateLoadingProperty)
            noTemplatesAvailable = label(MCDevBundle("creator.step.generic.no_templates_available.message"))
                .visible(false)
                .apply { component.foreground = JBColor.RED }
            templatePropertyPlaceholder = placeholder().align(AlignX.FILL)
        }.topGap(TopGap.SMALL)

        initTemplates()
    }

    private fun initTemplates() {
        selectedTemplate = EmptyLoadedTemplate

        val task = object : Task.Backgroundable(
            context.project,
            MCDevBundle("creator.step.generic.init_template_providers.message"),
            true,
            ALWAYS_BACKGROUND,
        ) {

            override fun run(indicator: ProgressIndicator) {
                if (project?.isDisposed == true) {
                    return
                }

                application.invokeAndWait({
                    ProgressManager.checkCanceled()
                    templateProvidersLoadingProperty.set(true)
                    VirtualFileManager.getInstance().syncRefresh()
                }, context.modalityState)

                for ((providerKey, repos) in templateRepos.groupBy { it.provider }) {
                    ProgressManager.checkCanceled()
                    val provider = TemplateProvider.get(providerKey)
                        ?: continue
                    indicator.text = provider.label
                    runCatching { provider.init(indicator, repos) }
                        .getOrLogException(logger<CustomPlatformStep>())
                }

                ProgressManager.checkCanceled()
                application.invokeAndWait({
                    ProgressManager.checkCanceled()
                    templateProvidersLoadingProperty.set(false)
                    // Force refresh to trigger template loading
                    templateRepoProperty.set(templateRepo)
                }, context.modalityState)
            }
        }

        val indicator = CreatorProgressIndicator(
            templateProvidersLoadingProperty,
            templateProvidersTextProperty,
            templateProvidersText2Property
        )
        ProgressManager.getInstance().runProcessWithProgressAsynchronously(task, indicator)
    }

    private fun loadTemplatesInBackground(provider: () -> Collection<LoadedTemplate>) {
        selectedTemplate = EmptyLoadedTemplate

        val task = object : Task.Backgroundable(
            context.project,
            MCDevBundle("creator.step.generic.load_template.message"),
            true,
            ALWAYS_BACKGROUND,
        ) {

            override fun run(indicator: ProgressIndicator) {
                if (project?.isDisposed == true) {
                    return
                }

                application.invokeAndWait({
                    ProgressManager.checkCanceled()
                    templateLoadingProperty.set(true)
                    VirtualFileManager.getInstance().syncRefresh()
                }, context.modalityState)

                ProgressManager.checkCanceled()
                val newTemplates = runCatching { provider() }
                    .getOrLogException(logger<CustomPlatformStep>())
                    ?: emptyList()

                ProgressManager.checkCanceled()
                application.invokeAndWait({
                    ProgressManager.checkCanceled()
                    templateLoadingProperty.set(false)
                    noTemplatesAvailable.visible(newTemplates.isEmpty())
                    availableTemplates = newTemplates
                }, context.modalityState)
            }
        }

        templateLoadingIndicator?.cancel()

        val indicator = CreatorProgressIndicator(
            templateLoadingProperty,
            templateLoadingTextProperty,
            templateLoadingText2Property
        )
        templateLoadingIndicator = indicator
        ProgressManager.getInstance().runProcessWithProgressAsynchronously(task, indicator)
    }

    override fun setupProject(project: Project) {
        templateProcessor.generateFiles(project, selectedTemplate)
    }
}
