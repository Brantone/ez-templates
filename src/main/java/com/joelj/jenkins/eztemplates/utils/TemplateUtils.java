package com.joelj.jenkins.eztemplates.utils;

import com.joelj.jenkins.eztemplates.TemplateImplementationProperty;
import com.joelj.jenkins.eztemplates.TemplateProperty;
import hudson.matrix.AxisList;
import hudson.matrix.MatrixBuild;
import hudson.matrix.MatrixProject;
import hudson.model.*;
import hudson.triggers.Trigger;
import hudson.triggers.TriggerDescriptor;
import hudson.util.CopyOnWriteList;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

@Singleton
public class TemplateUtils {
    private static final Logger LOG = Logger.getLogger("ez-templates");

    @Inject
    private ProjectUtils projectUtils;

    @Inject
    private ReflectionUtils reflectionUtils;

    public void handleTemplateSaved(AbstractProject templateProject, TemplateProperty property) throws IOException {
        LOG.info(String.format("Template [%s] was saved. Syncing implementations.", templateProject.getFullDisplayName()));
        for (AbstractProject impl : property.getImplementations()) {
            handleTemplateImplementationSaved(impl, TemplateImplementationProperty.from(impl));
        }
    }

    public void handleTemplateDeleted(AbstractProject templateProject, TemplateProperty property) throws IOException {
        LOG.info(String.format("Template [%s] was deleted.", templateProject.getFullDisplayName()));
        for (AbstractProject impl : property.getImplementations()) {
            LOG.info(String.format("Removing template from [%s].", impl.getFullDisplayName()));
            impl.removeProperty(TemplateImplementationProperty.class);
            projectUtils.silentSave(impl);
        }
    }

    public void handleTemplateRename(AbstractProject templateProject, TemplateProperty property, String oldFullName, String newFullName) throws IOException {
        LOG.info(String.format("Template [%s] was renamed. Updating implementations.", templateProject.getFullDisplayName()));
        for (AbstractProject impl : property.getImplementations(oldFullName)) {
            LOG.info(String.format("Updating template in [%s].", impl.getFullDisplayName()));
            TemplateImplementationProperty implProperty = TemplateImplementationProperty.from(impl);
            if (oldFullName.equals(implProperty.getTemplateJobName())) {
                implProperty.setTemplateJobName(newFullName);
                projectUtils.silentSave(impl);
            }
        }
    }

    public void handleTemplateImplementationSaved(AbstractProject implementationProject, TemplateImplementationProperty property) throws IOException {
        LOG.info(String.format("Implementation [%s] was saved. Syncing with [%s].", implementationProject.getFullDisplayName(), property.getTemplateJobName()));
        AbstractProject templateProject = projectUtils.findProject(property.getTemplateJobName());
        if (templateProject == null) {
            throw new IllegalStateException(String.format("Cannot find template [%s] used by job [%s]", property.getTemplateJobName(), implementationProject.getFullDisplayName()));
        }

        //Capture values we want to keep
        @SuppressWarnings("unchecked")
        boolean implementationIsTemplate = TemplateProperty.from(implementationProject) != null;
        List<ParameterDefinition> oldImplementationParameters = findParameters(implementationProject);
        @SuppressWarnings("unchecked")
        Map<TriggerDescriptor, Trigger> oldTriggers = implementationProject.getTriggers();
        boolean shouldBeDisabled = implementationProject.isDisabled();
        String description = implementationProject.getDescription();

        AxisList oldAxisList = null;
        if (implementationProject instanceof MatrixProject && !property.getSyncMatrixAxis()) {
            MatrixProject matrixProject = (MatrixProject) implementationProject;
            oldAxisList = matrixProject.getAxes();
        }

        implementationProject = synchronizeConfigFiles(implementationProject, templateProject);

        // Reverse all the fields that we've marked as "Don't Sync" so that they appear that they haven't changed.

        //Set values that we wanted to keep via reflection to prevent infinite save recursion
        fixProperties(implementationProject, property, implementationIsTemplate);
        fixParameters(implementationProject, oldImplementationParameters);

        if (!property.getSyncBuildTriggers()) {
            fixBuildTriggers(implementationProject, oldTriggers);
        }

        if (!property.getSyncDisabled()) {
            reflectionUtils.setFieldValue(AbstractProject.class, implementationProject, "disabled", shouldBeDisabled);
        }

        if (oldAxisList != null && implementationProject instanceof MatrixProject && !property.getSyncMatrixAxis()) {
            fixAxisList((MatrixProject) implementationProject, oldAxisList);
        }

        if (!property.getSyncDescription() && description != null) {
            reflectionUtils.setFieldValue(AbstractItem.class, implementationProject, "description", description);
        }

        projectUtils.silentSave(implementationProject);
    }

    /**
     * Inlined from {@link MatrixProject#setAxes(hudson.matrix.AxisList)} except it doesn't call save.
     *
     * @param matrixProject The project to set the Axis on.
     * @param axisList      The Axis list to set.
     */
    private void fixAxisList(MatrixProject matrixProject, AxisList axisList) {
        if (axisList == null) {
            return; //The "axes" field can never be null. So just to be extra careful.
        }
        reflectionUtils.setFieldValue(MatrixProject.class, matrixProject, "axes", axisList);

        //noinspection unchecked
        reflectionUtils.invokeMethod(MatrixProject.class, matrixProject, "rebuildConfigurations", ReflectionUtils.MethodParameter.get(MatrixBuild.MatrixBuildExecution.class, null));
    }

    private void fixBuildTriggers(AbstractProject implementationProject, Map<TriggerDescriptor, Trigger> oldTriggers) {
        List<Trigger<?>> triggersToReplace = projectUtils.getTriggers(implementationProject);
        if (triggersToReplace == null) {
            throw new NullPointerException("triggersToReplace");
        }

        if (!triggersToReplace.isEmpty() || !oldTriggers.isEmpty()) {
            //noinspection SynchronizationOnLocalVariableOrMethodParameter
            synchronized (triggersToReplace) {
                triggersToReplace.clear();
                for (Trigger trigger : oldTriggers.values()) {
                    triggersToReplace.add(trigger);
                }
            }
        }
    }

    private void fixParameters(AbstractProject implementationProject, List<ParameterDefinition> oldImplementationParameters) throws IOException {
        List<ParameterDefinition> newImplementationParameters = findParameters(implementationProject);

        ParametersDefinitionProperty newParameterAction = findParametersToKeep(oldImplementationParameters, newImplementationParameters);
        @SuppressWarnings("unchecked") ParametersDefinitionProperty toRemove = (ParametersDefinitionProperty) implementationProject.getProperty(ParametersDefinitionProperty.class);
        if (toRemove != null) {
            //noinspection unchecked
            implementationProject.removeProperty(toRemove);
        }
        if (newParameterAction != null) {
            //noinspection unchecked
            implementationProject.addProperty(newParameterAction);
        }
    }

    private ParametersDefinitionProperty findParametersToKeep(List<ParameterDefinition> oldImplementationParameters, List<ParameterDefinition> newImplementationParameters) {
        List<ParameterDefinition> result = new LinkedList<ParameterDefinition>();
        for (ParameterDefinition newImplementationParameter : newImplementationParameters) { //'new' parameters are the same as the template.
            boolean found = false;
            Iterator<ParameterDefinition> iterator = oldImplementationParameters.iterator();
            while (iterator.hasNext()) {
                ParameterDefinition oldImplementationParameter = iterator.next();
                if (newImplementationParameter.getName().equals(oldImplementationParameter.getName())) {
                    found = true;
                    iterator.remove(); //Make the next iteration a little faster.
                    // #17 Description on parameters should always be overridden by template
                    reflectionUtils.setFieldValue(ParameterDefinition.class, oldImplementationParameter, "description", newImplementationParameter.getDescription());
                    result.add(oldImplementationParameter);
                }
            }
            if (!found) {
                //Add new parameters not accounted for.
                result.add(newImplementationParameter);
                LOG.info(String.format("\t+++ new parameter [%s]", newImplementationParameter.getName()));
            }
        }

        if (oldImplementationParameters != null) {
            for (ParameterDefinition unused : oldImplementationParameters) {
                LOG.info(String.format("\t--- old parameter [%s]", unused.getName()));
            }
        }

        return result.isEmpty() ? null : new ParametersDefinitionProperty(result);
    }

    private AbstractProject synchronizeConfigFiles(AbstractProject implementationProject, AbstractProject templateProject) throws IOException {
        File templateConfigFile = templateProject.getConfigFile().getFile();
        BufferedReader reader = new BufferedReader(new FileReader(templateConfigFile));
        try {
            Source source = new StreamSource(reader);
            implementationProject = projectUtils.updateProjectWithXmlSource(implementationProject, source);
        } finally {
            reader.close();
        }
        return implementationProject;
    }

    private List<ParameterDefinition> findParameters(AbstractProject implementationProject) {
        List<ParameterDefinition> definitions = new LinkedList<ParameterDefinition>();
        @SuppressWarnings("unchecked")
        ParametersDefinitionProperty parametersDefinitionProperty = (ParametersDefinitionProperty) implementationProject.getProperty(ParametersDefinitionProperty.class);
        if (parametersDefinitionProperty != null) {
            for (String parameterName : parametersDefinitionProperty.getParameterDefinitionNames()) {
                definitions.add(parametersDefinitionProperty.getParameterDefinition(parameterName));
            }
        }
        return definitions;
    }

    private void fixProperties(AbstractProject implementationProject, TemplateImplementationProperty property, boolean implementationIsTemplate) throws IOException {
        CopyOnWriteList<JobProperty<?>> properties = reflectionUtils.getFieldValue(Job.class, implementationProject, "properties");
        properties.add(property);

        if (!implementationIsTemplate) {
            for (JobProperty<?> jobProperty : properties) {
                if (jobProperty instanceof TemplateProperty) {
                    properties.remove(jobProperty);
                }
            }
        }
    }

}
