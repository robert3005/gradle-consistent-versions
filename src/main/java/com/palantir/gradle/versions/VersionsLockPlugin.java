/*
 * (c) Copyright 2019 Palantir Technologies Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.gradle.versions;

import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.MapDifference;
import com.google.common.collect.MapDifference.ValueDifference;
import com.google.common.collect.Maps;
import com.palantir.configurationresolver.ConfigurationResolverPlugin;
import com.palantir.configurationresolver.ResolveConfigurationsTask;
import com.palantir.gradle.versions.internal.MyModuleIdentifier;
import com.palantir.gradle.versions.internal.MyModuleVersionIdentifier;
import com.palantir.gradle.versions.lockstate.Dependents;
import com.palantir.gradle.versions.lockstate.FullLockState;
import com.palantir.gradle.versions.lockstate.Line;
import com.palantir.gradle.versions.lockstate.LockState;
import com.palantir.gradle.versions.lockstate.LockStates;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import netflix.nebula.dependency.recommender.RecommendationStrategies;
import netflix.nebula.dependency.recommender.provider.RecommendationProviderContainer;
import org.gradle.api.GradleException;
import org.gradle.api.NamedDomainObjectProvider;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.DependencyConstraint;
import org.gradle.api.artifacts.DependencySet;
import org.gradle.api.artifacts.ProjectDependency;
import org.gradle.api.artifacts.ResolvableDependencies;
import org.gradle.api.artifacts.VersionConstraint;
import org.gradle.api.artifacts.component.ComponentSelector;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentSelector;
import org.gradle.api.artifacts.dsl.DependencyConstraintHandler;
import org.gradle.api.artifacts.result.ResolutionResult;
import org.gradle.api.artifacts.result.ResolvedComponentResult;
import org.gradle.api.artifacts.result.UnresolvedDependencyResult;
import org.gradle.api.attributes.Attribute;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.plugins.JavaPlugin;
import org.jetbrains.annotations.NotNull;

public class VersionsLockPlugin implements Plugin<Project> {
    private static final Logger log = Logging.getLogger(VersionsLockPlugin.class);

    /**
     * Root project configuration that collects all the dependencies from the
     * {@link #SUBPROJECT_UNIFIED_CONFIGURATION_NAME} of each project.
     */
    static final String UNIFIED_CLASSPATH_CONFIGURATION_NAME = "unifiedClasspath";
    /**
     * Per-project configuration that extends the configurations whose dependencies we are interested in.
     */
    private static final String SUBPROJECT_UNIFIED_CONFIGURATION_NAME = "subprojectUnifiedClasspath";
    /** Configuration to which we apply the constraints from the lock file. */
    private static final String LOCK_CONSTRAINTS_CONFIGURATION_NAME = "lockConstraints";
    private static final Attribute<Boolean> CONSISTENT_VERSIONS_CONSTRAINT_ATTRIBUTE =
            Attribute.of("consistent-versions", Boolean.class);
    /**
     * Copied from {@link org.gradle.api.internal.artifacts.dsl.dependencies.PlatformSupport#COMPONENT_CATEGORY} since
     * that's internal.
     */
    private static final Attribute<String> COMPONENT_CATEGORY =
            Attribute.of("org.gradle.component.category", String.class);

    @NotNull
    static Path getRootLockFile(Project project) {
        return project.file("versions.lock").toPath();
    }

    @Override
    public final void apply(Project project) {
        checkPreconditions(project);

        project.getPluginManager().apply(ConfigurationResolverPlugin.class);

        Configuration unifiedClasspath = project
                .getConfigurations()
                .create(UNIFIED_CLASSPATH_CONFIGURATION_NAME, c -> {
                    c.setVisible(false).setCanBeConsumed(false);
                });

        project.allprojects(subproject -> {
            sourceDependenciesFromProject(project, unifiedClasspath, subproject);
        });

        Path rootLockfile = getRootLockFile(project);

        Supplier<FullLockState> fullLockStateSupplier = Suppliers.memoize(() -> {
            ResolutionResult resolutionResult = unifiedClasspath.getIncoming().getResolutionResult();
            return computeLockState(resolutionResult);
        });

        // We apply 'java-base' because we need the JavaEcosystemVariantDerivationStrategy for platforms to work
        // (but that's internal)
        // TODO(dsanduleac): we will move to java-platform in the future (probably once 5.2 is out)
        // See: https://github.com/gradle/gradle/pull/7967
        project.getPluginManager().apply("java-base");

        if (project.getGradle().getStartParameter().isWriteDependencyLocks()) {
            // Gradle will break if you try to add constraints to any configurations that have been resolved.
            // Since unifiedClasspath depends on the SUBPROJECT_UNIFIED_CONFIGURATION_NAME configuration of all
            // subprojects (above), that would resolve them when we resolve unifiedClasspath. We need this workaround
            // to enable the workflow:
            //
            //  1. when 'unifiedClasspath' is resolved with --write-locks, it writes the lock file and resolves its
            //     dependencies
            //  2. read the lock file
            //  3. enforce these versions on all subprojects, using constraints
            //
            // Since we can't apply these constraints to the already resolved configurations, we need a workaround to
            // ensure that unifiedClasspath does not directly depend on subproject configurations that we intend to
            // enforce constraints on.

            // Recursively copy all project configurations that are depended on.
            unifiedClasspath.withDependencies(depSet -> {
                Map<Configuration, String> copiedConfigurationsCache = new HashMap<>();
                resolveDependentPublications(project, depSet, copiedConfigurationsCache);
            });

            // Must wire up the constraint configuration to right AFTER rootProject has written theirs
            unifiedClasspath.getIncoming().afterResolve(r -> {
                failIfAnyDependenciesUnresolved(r);

                new ConflictSafeLockFile(rootLockfile).writeLocks(fullLockStateSupplier.get());
                log.lifecycle("Finished writing lock state to {}", rootLockfile);
                configureAllProjectsUsingConstraints(project, rootLockfile);
            });

            // If you only run ':subproject:resolveConfigurations --write-locks', gradle wouldn't resolve the root
            // unifiedClasspath configuration, which would behave differently than if we ran 'resolveConfigurations'.
            // Workaround is we always force the 'unifiedClasspath' to be resolved.

            // Note: we don't use project.getGradle().projectsEvaluated() as gradle warns us against resolving the
            // configuration at that point. See https://docs.gradle.org/5.1/userguide/troubleshooting_dependency_resolution.html#sub:configuration_resolution_constraints
            project.afterEvaluate(p -> {
                p.evaluationDependsOnChildren();
                unifiedClasspath.getIncoming().getResolutionResult().getRoot();
            });
        } else {
            if (Files.notExists(rootLockfile)) {
                log.warn("Root lock file '{}' doesn't exist, please run "
                        + "`./gradlew --write-locks` to initialise locks", rootLockfile);
                return;
            }

            // Ensure that we throw if there are dependencies that are not present in the lock state.
            unifiedClasspath.getIncoming().afterResolve(r -> {
                failIfAnyDependenciesUnresolved(r);

                LockState currentLockState = LockStates.toLockState(fullLockStateSupplier.get());
                LockState persistedLockState = new ConflictSafeLockFile(rootLockfile).readLocks();
                ensureLockStateIsUpToDate(currentLockState, persistedLockState);
            });

            // Can configure using constraints immediately, because rootLockfile exists.
            configureAllProjectsUsingConstraints(project, rootLockfile);

            project.getTasks().register("why", WhyDependencyTask.class, t -> {
                t.lockfile(rootLockfile);
                t.fullLockState(project.provider(fullLockStateSupplier::get));
            });
        }
    }

    private static void ensureLockStateIsUpToDate(LockState currentLockState, LockState persistedLockState) {
        MapDifference<MyModuleIdentifier, Line> difference = Maps.difference(
                persistedLockState.linesByModuleIdentifier(), currentLockState.linesByModuleIdentifier());

        Set<MyModuleIdentifier> missing = difference.entriesOnlyOnLeft().keySet();
        if (!missing.isEmpty()) {
            throw new RuntimeException(
                    "Locked dependencies missing from the resolution result: " + missing + ". "
                            + ". Please run './gradlew --write-locks'.");
        }

        Set<MyModuleIdentifier> unknown = difference.entriesOnlyOnRight().keySet();
        if (!unknown.isEmpty()) {
            throw new RuntimeException(
                    "Found dependencies that were not in the lock state: " + unknown + ". "
                            + "Please run './gradlew --write-locks'.");
        }

        Map<MyModuleIdentifier, ValueDifference<Line>> differing = difference.entriesDiffering();
        if (!differing.isEmpty()) {
            throw new RuntimeException(
                    "Found dependencies whose dependents changed: " + differing + ". "
                            + "Please run './gradlew --write-locks'.");
        }
    }

    private static void sourceDependenciesFromProject(
            Project rootProject, Configuration unifiedClasspath, Project project) {
        // Parallel 'resolveConfigurations' sometimes breaks unless we force the root one to run first.
        if (rootProject != project) {
            project.getTasks().withType(
                    ResolveConfigurationsTask.class, task -> task.mustRunAfter(":resolveConfigurations"));
        }

        // Since we apply the forces to compileClasspath, runtimeClasspath, we should at least check what
        // dependencies they have.
        project.getPluginManager().withPlugin("base", plugin -> {
            project.getConfigurations().register(SUBPROJECT_UNIFIED_CONFIGURATION_NAME, conf -> {
                conf.setVisible(false).setCanBeResolved(false);
                conf.extendsFrom(project.getConfigurations().getByName(Dependency.DEFAULT_CONFIGURATION));
                // Mark it so it doesn't receive constraints from VersionsPropsPlugin
                conf.getAttributes().attribute(VersionsPropsPlugin.CONFIGURATION_EXCLUDE_ATTRIBUTE, true);
            });
            // Depend on this "sink" configuration from our global aggregating configuration `unifiedClasspath`.
            Dependency projectDep = rootProject.getDependencies().project(ImmutableMap.of(
                            "path", project.getPath(),
                            "configuration", SUBPROJECT_UNIFIED_CONFIGURATION_NAME));
            unifiedClasspath.getDependencies().add(projectDep);
        });
        project.getPluginManager().withPlugin("java", plugin -> {
            project.getConfigurations().named(SUBPROJECT_UNIFIED_CONFIGURATION_NAME).configure(conf -> {
                Stream.of(
                        JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME,
                        JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME)
                        .map(project.getConfigurations()::getByName)
                        .forEach(conf::extendsFrom);
            });
        });
    }

    private static void checkPreconditions(Project project) {
        if (!project.getRootProject().equals(project)) {
            throw new GradleException("Must be applied only to root project");
        }

        project.subprojects(subproject -> {
            subproject.afterEvaluate(sub -> {
                if (haveSameGroupAndName(project, sub)) {
                    throw new GradleException(String.format("This plugin doesn't work if the root project shares both "
                            + "group and name with a subproject. Consider adding the following to settings.gradle:\n"
                            + "rootProject.name = '%s-root'", project.getName()));
                }
            });
        });

        project.subprojects(subproject -> {
            subproject.afterEvaluate(sub -> {
                sub.getConfigurations().configureEach(conf -> {
                    org.gradle.api.internal.artifacts.configurations.ConflictResolution conflictResolution =
                            ((org.gradle.api.internal.artifacts.configurations.ResolutionStrategyInternal)
                                    conf.getResolutionStrategy()).getConflictResolution();
                    if (conflictResolution
                            == org.gradle.api.internal.artifacts.configurations.ConflictResolution.strict) {
                        throw new GradleException("Must not use failOnVersionConflict() for " + conf);
                    }
                });
                sub.getPluginManager().withPlugin("nebula.dependency-recommender", plugin -> {
                    RecommendationProviderContainer container =
                            sub.getExtensions().findByType(RecommendationProviderContainer.class);
                    if (container.getStrategy() == RecommendationStrategies.OverrideTransitives) {
                        throw new GradleException("Must not use strategy OverrideTransitives for " + sub + ". "
                                + "Use this instead: dependencyRecommendations { strategy ConflictResolved }");
                    }
                });
            });
        });
    }

    /**
     * Recursive method that copies unseen {@link ProjectDependency project dependencies} found in the given {@link
     * DependencySet}, and then amends their {@link ProjectDependency#getTargetConfiguration()} to point to the copied
     * configuration. It then configures any copied  recursively through {@link Configuration#withDependencies} which is
     * lazy, so recursive calls don't actually execute right away, but are executed when those configurations are
     * evaluated.
     */
    private static void resolveDependentPublications(
            Project currentProject, DependencySet dependencySet, Map<Configuration, String> copiedConfigurationsCache) {
        dependencySet
                .matching(dependency -> ProjectDependency.class.isAssignableFrom(dependency.getClass()))
                .configureEach(dependency -> {
                    ProjectDependency projectDependency = (ProjectDependency) dependency;
                    Project projectDep = projectDependency.getDependencyProject();
                    String targetConfiguration = Optional
                            .ofNullable(projectDependency.getTargetConfiguration())
                            .orElse(Dependency.DEFAULT_CONFIGURATION);

                    // We can depend on other configurations from the same project, so don't introduce a cycle.
                    if (projectDep != currentProject) {
                        currentProject.evaluationDependsOn(projectDep.getPath());
                    }

                    Configuration targetConf = projectDep.getConfigurations().getByName(targetConfiguration);
                    Preconditions.checkNotNull(targetConf,
                            "Target configuration of project dependency was null: %s -> %s",
                            currentProject,
                            projectDep);

                    if (copiedConfigurationsCache.containsKey(targetConf)) {
                        String copiedConf = copiedConfigurationsCache.get(targetConf);
                        log.debug("Re-using already copied target configuration for dep {} -> {}: {}",
                                currentProject, targetConf, copiedConf);
                        projectDependency.setTargetConfiguration(copiedConf);
                        return;
                    }

                    Configuration copiedConf = targetConf.copyRecursive();
                    copiedConf.setDescription(String.format("Copy of the '%s' configuration that can be resolved by "
                                    + "com.palantir.consistent-versions without resolving the '%s' configuration "
                                    + "itself.",
                            targetConf.getName(), targetConf.getName()));

                    // Update state about what we've seen
                    copiedConfigurationsCache.put(targetConf, copiedConf.getName());

                    if (log.isInfoEnabled()) {
                        log.info(
                                "Recursively copied {}'s '{}' configuration, which has\n"
                                        + " - dependencies: {}\n"
                                        + " - constraints: {}",
                                projectDep, targetConfiguration,
                                ImmutableList.copyOf(copiedConf.getAllDependencies()),
                                ImmutableList.copyOf(copiedConf.getAllDependencyConstraints()));
                    }

                    projectDep.getConfigurations().add(copiedConf);

                    projectDependency.setTargetConfiguration(copiedConf.getName());
                    resolveDependentPublications(projectDep, copiedConf.getDependencies(), copiedConfigurationsCache);
                });
    }

    private static boolean haveSameGroupAndName(Project project, Project subproject) {
        return project.getName().equals(subproject.getName())
                && project.getGroup().equals(subproject.getGroup());
    }

    private static void failIfAnyDependenciesUnresolved(ResolvableDependencies resolvableDependencies) {
        List<UnresolvedDependencyResult> unresolved = resolvableDependencies
                .getResolutionResult()
                .getAllDependencies()
                .stream()
                .filter(a -> a instanceof UnresolvedDependencyResult)
                .map(a -> (UnresolvedDependencyResult) a)
                .collect(Collectors.toList());
        if (!unresolved.isEmpty()) {
            throw new GradleException(String.format(
                    "Could not write lock for %s due to unresolved dependencies:\n%s",
                    UNIFIED_CLASSPATH_CONFIGURATION_NAME,
                    unresolved
                            .stream()
                            .map(VersionsLockPlugin::formatUnresolvedDependencyResult)
                            .collect(Collectors.joining("\n"))));
        }
    }

    /**
     * Assumes the resolution result succeeded, that is, {@link #failIfAnyDependenciesUnresolved} was run and didn't
     * throw.
     */
    private static FullLockState computeLockState(ResolutionResult resolutionResult) {
        FullLockState.Builder builder = FullLockState.builder();
        resolutionResult.allComponents(component -> {
            extractDependents(component).ifPresent(dependents ->
                    builder.putLines(MyModuleVersionIdentifier.copyOf(component.getModuleVersion()), dependents));
        });
        return builder.build();
    }

    private static Optional<Dependents> extractDependents(ResolvedComponentResult component) {
        if (!(component.getId() instanceof ModuleComponentIdentifier)) {
            return Optional.empty();
        }
        // Don't lock any platforms, since these are not actual dependencies.
        if (component.getDependents().stream().anyMatch(rdr -> {
            String category = rdr.getRequested().getAttributes().getAttribute(COMPONENT_CATEGORY);
            return category != null && category.contains("platform");
        })) {
            log.debug("Not locking component because it's a platform: {}", component.getId());
            return Optional.empty();
        }
        return Optional.of(Dependents.of(component
                .getDependents()
                .stream()
                .filter(dep -> !dep.getRequested().getAttributes().contains(CONSISTENT_VERSIONS_CONSTRAINT_ATTRIBUTE))
                .collect(Collectors.groupingBy(
                        dep -> dep.getFrom().getId(),
                        () -> new TreeMap<>(GradleComparators.COMPONENT_IDENTIFIER_COMPARATOR),
                        Collectors.mapping(
                                dep -> getRequestedVersionConstraint(dep.getRequested()),
                                Collectors.toCollection(
                                        () -> new TreeSet<>(Comparator.comparing(VersionConstraint::toString))))))));
    }

    private static VersionConstraint getRequestedVersionConstraint(ComponentSelector requested) {
        if (requested instanceof ModuleComponentSelector) {
            return ((ModuleComponentSelector) requested).getVersionConstraint();
        }
        throw new RuntimeException(String.format("Expecting a ModuleComponentSelector but found a %s: %s",
                requested.getClass(), requested));
    }

    /**
     * Essentially replicates what
     * {@link org.gradle.api.tasks.diagnostics.internal.insight.DependencyInsightReporter#collectErrorMessages}
     * does, since that whole class is not public API.
     */
    private static String formatUnresolvedDependencyResult(UnresolvedDependencyResult result) {
        StringBuilder failures = new StringBuilder();
        for (Throwable failure = result.getFailure(); failure != null; failure = failure.getCause()) {
            failures.append("         - ");
            failures.append(failure.getMessage());
            failures.append("\n");
        }
        return String.format(" * %s (requested: '%s' because: %s)\n      Failures:\n%s",
                result.getAttempted(),
                result.getRequested(),
                result.getAttemptedReason(),
                failures);
    }

    private static void configureAllProjectsUsingConstraints(Project rootProject, Path gradleLockfile) {
        // Construct constraints for all versions locked by root project.
        List<DependencyConstraint> constraints =
                constructConstraints(gradleLockfile, rootProject.getDependencies().getConstraints());

        rootProject.allprojects(subproject -> configureUsingConstraints(subproject, constraints));
    }

    private static void configureUsingConstraints(
            Project subproject, List<DependencyConstraint> constraints) {
        log.info("Configuring locks for {} using constraints", subproject.getPath());
        // Configure constraints on all configurations that should be locked.
        createTopConfiguration(subproject)
                .configure(conf -> constraints.stream().forEach(conf.getDependencyConstraints()::add));
    }

    private static NamedDomainObjectProvider<Configuration> createTopConfiguration(Project project) {

        NamedDomainObjectProvider<Configuration> locksConfiguration =
                project.getConfigurations().register(LOCK_CONSTRAINTS_CONFIGURATION_NAME, conf -> {
                    conf.setVisible(false);
                    conf.setCanBeConsumed(false);
                    conf.setCanBeResolved(false);
                });

        ImmutableSet<String> configurationNames = ImmutableSet.of(
                Dependency.DEFAULT_CONFIGURATION,
                JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME,
                JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME);
        project
                .getConfigurations()
                .matching(conf -> configurationNames.contains(conf.getName()))
                .configureEach(conf -> conf.extendsFrom(locksConfiguration.get()));

        return locksConfiguration;
    }

    @NotNull
    private static List<DependencyConstraint> constructConstraints(
            Path gradleLockfile, DependencyConstraintHandler constraintHandler) {
        return new ConflictSafeLockFile(gradleLockfile)
                .readLocks()
                .linesByModuleIdentifier()
                .entrySet()
                .stream()
                .map(e -> e.getKey() + ":" + e.getValue().version())
                // Note: constraints.create sets the version as preferred + required, we want 'strictly' just like
                // gradle does when verifying a lock file.
                .map(notation -> constraintHandler.create(notation, constraint -> {
                    constraint.version(v -> v.strictly(Objects.requireNonNull(constraint.getVersion())));
                    constraint.because("Locked by versions.lock");
                    // We set this in order to identify these constraints later.
                    constraint.attributes(attributeContainer -> {
                        attributeContainer.attribute(CONSISTENT_VERSIONS_CONSTRAINT_ATTRIBUTE, true);
                    });
                }))
                .collect(Collectors.toList());
    }
}
