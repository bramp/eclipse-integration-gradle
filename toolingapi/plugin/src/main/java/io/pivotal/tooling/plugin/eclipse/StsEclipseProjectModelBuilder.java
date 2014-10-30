package io.pivotal.tooling.plugin.eclipse;

import io.pivotal.tooling.model.eclipse.StsEclipseProject;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.result.ArtifactResult;
import org.gradle.api.artifacts.result.ComponentArtifactsResult;
import org.gradle.api.artifacts.result.DependencyResult;
import org.gradle.api.artifacts.result.ResolvedDependencyResult;
import org.gradle.api.internal.artifacts.DefaultModuleVersionIdentifier;
import org.gradle.api.internal.artifacts.component.DefaultModuleComponentIdentifier;
import org.gradle.api.internal.artifacts.component.DefaultModuleComponentSelector;
import org.gradle.api.internal.artifacts.dependencies.DefaultExternalModuleDependency;
import org.gradle.api.internal.artifacts.result.DefaultResolvedArtifactResult;
import org.gradle.api.specs.Specs;
import org.gradle.language.base.artifact.SourcesArtifact;
import org.gradle.language.java.artifact.JavadocArtifact;
import org.gradle.plugins.ide.internal.tooling.EclipseModelBuilder;
import org.gradle.plugins.ide.internal.tooling.GradleProjectBuilder;
import org.gradle.plugins.ide.internal.tooling.eclipse.DefaultEclipseProject;
import org.gradle.plugins.ide.internal.tooling.eclipse.DefaultEclipseProjectDependency;
import org.gradle.runtime.jvm.JvmLibrary;
import org.gradle.tooling.internal.gradle.DefaultGradleModuleVersion;
import org.gradle.tooling.internal.gradle.DefaultGradleProject;
import org.gradle.tooling.model.GradleModuleVersion;
import org.gradle.tooling.model.eclipse.HierarchicalEclipseProject;
import org.gradle.tooling.provider.model.ToolingModelBuilder;

import java.util.*;

class StsEclipseProjectModelBuilder implements ToolingModelBuilder {
    private DefaultStsEclipseProject result, root;
    private Project currentProject;

    private GradleProjectBuilder gradleProjectBuilder = new GradleProjectBuilder();
    private DefaultGradleProject rootGradleProject;

    private EclipseModelBuilder eclipseModelBuilder = new EclipseModelBuilder(gradleProjectBuilder);

    private Map<String, GradleModuleVersion> moduleVersionByProjectName = new HashMap<String, GradleModuleVersion>();

    @Override
    public boolean canBuild(String modelName) {
        return modelName.equals(StsEclipseProject.class.getName());
    }

    @Override
    public Object buildAll(String modelName, Project project) {
        currentProject = project;
        rootGradleProject = gradleProjectBuilder.buildAll(project);
        buildHierarchy(project.getRootProject());
        buildProjectDependencies(root);
        return result;
    }

    /**
     * @param project
     * @return - A list of all binary dependencies, including transitives of both
     * binary dependencies and project dependencies
     */
    private static List<DefaultStsEclipseExternalDependency> buildExternalDependencies(Project project) {
        boolean hasCompile = false;
        for(Configuration conf : project.getConfigurations())
            if(conf.getName().equals("compile"))
                hasCompile = true;
        if(!hasCompile)
            return Collections.EMPTY_LIST;

        Map<String, DefaultStsEclipseExternalDependency> externalDependenciesById = new HashMap<String, DefaultStsEclipseExternalDependency>();

        List<ComponentIdentifier> binaryDependencies = new ArrayList<ComponentIdentifier>();
        for (DependencyResult dep : project.getConfigurations().getByName("compile").getIncoming().getResolutionResult().getAllDependencies()) {
            if(dep instanceof ResolvedDependencyResult && dep.getRequested() instanceof DefaultModuleComponentSelector)
                binaryDependencies.add(((ResolvedDependencyResult) dep).getSelected().getId());
        }

        List<String> binaryDependenciesAsStrings = new ArrayList<String>();
        for (ComponentIdentifier binaryDependency : binaryDependencies)
            binaryDependenciesAsStrings.add(binaryDependency.toString());

        Set<ComponentArtifactsResult> artifactsResults = project.getDependencies().createArtifactResolutionQuery()
                .forComponents(binaryDependencies)
                .withArtifacts(JvmLibrary.class, SourcesArtifact.class, JavadocArtifact.class)
                .execute()
                .getResolvedComponents();

        for (ResolvedArtifact artifact : project.getConfigurations().getByName("compile").getResolvedConfiguration().getLenientConfiguration().getArtifacts(Specs.SATISFIES_ALL)) {
            ModuleVersionIdentifier id = artifact.getModuleVersion().getId();

            if(binaryDependenciesAsStrings.contains(id.toString())) {
                externalDependenciesById.put(id.toString(), new DefaultStsEclipseExternalDependency()
                        .setFile(artifact.getFile())
                        .setModuleVersion(new DefaultModuleVersionIdentifier(id.getGroup(), id.getName(), id.getVersion())));
            }
        }

        for (ComponentArtifactsResult artifactResult : artifactsResults) {
            DefaultStsEclipseExternalDependency externalDependency = externalDependenciesById.get(artifactResult.getId().toString());
            for (ArtifactResult sourcesResult : artifactResult.getArtifacts(SourcesArtifact.class)) {
                if(sourcesResult instanceof DefaultResolvedArtifactResult)
                    externalDependency.setSource(((DefaultResolvedArtifactResult) sourcesResult).getFile());
            }
            for (ArtifactResult javadocResult : artifactResult.getArtifacts(JavadocArtifact.class)) {
                if(javadocResult instanceof DefaultResolvedArtifactResult)
                    externalDependency.setJavadoc(((DefaultResolvedArtifactResult) javadocResult).getFile());
            }
        }

        // must create new list because Map.values() is not Serializable
        return new ArrayList<DefaultStsEclipseExternalDependency>(externalDependenciesById.values());
    }

    private static DefaultStsEclipseExternalDependency resolveExternalDependencyEquivalent(Project project) {
        String group = project.getGroup().toString(), name = project.getName();

        EclipseToolingModelPluginExtension ext = (EclipseToolingModelPluginExtension) project.getExtensions().getByName("eclipseToolingModel");

        Configuration projectExternal = project.getConfigurations().create("projectExternal");
        projectExternal.getDependencies().add(new DefaultExternalModuleDependency(group,
                name, ext.getEquivalentBinaryVersion()).setTransitive(false));

        DefaultStsEclipseExternalDependency externalDependency = new DefaultStsEclipseExternalDependency();

        for (ResolvedArtifact resolvedArtifact : projectExternal.getResolvedConfiguration().getLenientConfiguration().getArtifacts(Specs.SATISFIES_ALL)) {
            externalDependency.setModuleVersion(new DefaultModuleVersionIdentifier(group, name,
                    resolvedArtifact.getModuleVersion().getId().getVersion()));
            externalDependency.setFile(resolvedArtifact.getFile());
        }

        if(externalDependency.getFile() == null)
            return null; // unable to find a binary equivalent for this project

        Set<ComponentArtifactsResult> artifactsResults = project.getDependencies().createArtifactResolutionQuery()
                .forComponents(new DefaultModuleComponentIdentifier(group, name, externalDependency.getGradleModuleVersion().getVersion()))
                .withArtifacts(JvmLibrary.class, SourcesArtifact.class, JavadocArtifact.class)
                .execute()
                .getResolvedComponents();

        for (ComponentArtifactsResult artifactResult : artifactsResults) {
            for (ArtifactResult sourcesResult : artifactResult.getArtifacts(SourcesArtifact.class)) {
                if(sourcesResult instanceof DefaultResolvedArtifactResult)
                    externalDependency.setSource(((DefaultResolvedArtifactResult) sourcesResult).getFile());
            }
            for (ArtifactResult javadocResult : artifactResult.getArtifacts(JavadocArtifact.class)) {
                if(javadocResult instanceof DefaultResolvedArtifactResult)
                    externalDependency.setJavadoc(((DefaultResolvedArtifactResult) javadocResult).getFile());
            }
        }

        return externalDependency;
    }

    private DefaultStsEclipseProject buildHierarchy(Project project) {
        DefaultStsEclipseProject eclipseProject = new DefaultStsEclipseProject();

        if (project == project.getRootProject())
            root = eclipseProject;

        List<DefaultStsEclipseProject> children = new ArrayList<DefaultStsEclipseProject>();
        for (Project child : project.getChildProjects().values())
            children.add(buildHierarchy(child));

        moduleVersionByProjectName.put(project.getName(), new DefaultGradleModuleVersion(new DefaultModuleVersionIdentifier(project.getGroup().toString(),
                project.getName(), project.getVersion().toString())));

        DefaultEclipseProject defaultEclipseProject = eclipseModelBuilder.buildAll(HierarchicalEclipseProject.class.getName(), project);

        eclipseProject
                .setHierarchicalEclipseProject(defaultEclipseProject)
                .setGradleProject(rootGradleProject.findByPath(project.getPath()))
                .setChildren(children)
                .setExternalEquivalent(resolveExternalDependencyEquivalent(project))
                .setClasspath(buildExternalDependencies(project))
                .setPlugins(plugins(project))
                .setRoot(root);

        for (DefaultStsEclipseProject child : children)
            child.setParent(eclipseProject);

        if (project == currentProject)
            result = eclipseProject;

        return eclipseProject;
    }

    private void buildProjectDependencies(DefaultStsEclipseProject eclipseProject) {
        List<DefaultStsEclipseProjectDependency> projectDependencies = new ArrayList<DefaultStsEclipseProjectDependency>();
        for (DefaultEclipseProjectDependency projectDependency : eclipseProject.getHierarchicalEclipseProject().getProjectDependencies()) {
            projectDependencies.add(new DefaultStsEclipseProjectDependency(projectDependency,
                    moduleVersionByProjectName.get(projectDependency.getTargetProject().getName())));
        }

        eclipseProject.setProjectDependencies(projectDependencies);

        for (DefaultStsEclipseProject child : eclipseProject.getChildren())
            buildProjectDependencies(child);
    }

    private static List<String> plugins(Project project) {
        List<String> plugins = new ArrayList<String>();
        for(Plugin plugin : project.getPlugins())
            plugins.add(plugin.getClass().getName());
        return plugins;
    }
}
