# com.palantir.consistent-versions [ ![Download](https://api.bintray.com/packages/palantir/releases/gradle-consistent-versions/images/download.svg) ](https://bintray.com/palantir/releases/gradle-consistent-versions/_latestVersion)

_A gradle plugin to ensure your dependency versions are *consistent* across all subprojects, without requiring you to hunt down and force every single conflicting transitive dependency._

Direct dependencies are specified in a top level `versions.props` file and then the plugin uses [constraints][dependency constraints] to figure out a sensible version for all transitive dependencies and writes out the whole thing to `versions.lock`.

**Run `./gradlew --write-locks` to update dependencies.**

```diff
 buildscript {
     dependencies {
+        classpath 'com.palantir.gradle.consistentversions:gradle-consistent-versions:<latest>'
     }
 }

+apply plugin: 'com.palantir.consistent-versions'

 allprojects {
     dependencies {
+        rootConfiguration platform('com.palantir.witchcraft:witchcraft-core-bom')
     }

-    dependencyRecommendations {
-        mavenBom module: 'com.palantir.witchcraft:witchcraft-core-bom'
-    }

-    configurations.all {
-        resolutionStrategy {
-            failOnVersionConflict()
-        }
-    }
 }
```

Add the following to your settings.gradle to fully disable nebula.

```diff
 org.gradle.parallel=true
+com.palantir.baseline-versions.disable=true
```

You can also likely delete the 'conflict resolution' section of your versions.props. To understand why a particular version appears in your lockfile, use  **`./gradlew why --hash a60c3ce8`**.

### An evolution of `nebula.dependency-recommender`

[nebula.dependency-recommender]: https://github.com/nebula-plugins/nebula-dependency-recommender-plugin
[dependency constraints]: https://docs.gradle.org/current/userguide/managing_transitive_dependencies.html#sec:dependency_constraints
[gradle BOM import]: https://docs.gradle.org/5.1/userguide/managing_transitive_dependencies.html#sec:bom_import

Unlike [nebula.dependency-recommender][], where versions declared in `versions.props` are *forced*, ignoring constraints
from transitives that might want a higher version, this plugin injects versions as gradle [dependency constraints][],
which play nicely with version constraints that come from the POM files of transitives.

This fixes the issue with `nebula.dependency-recommender` where a currently forced version later ends up silently
downgrading a transitive, eliminating runtime errors such as `ClassNotFoundException`, `NoSuchMethodException` etc.

### failOnVersionConflict() considered harmful

Many projects use `failOnVersionConflict()` and nebula dependency recommender's `OverrideTransitives` to ensure they have exactly one version of each jar across all the subprojects in one repo.

- **Problem:** devs pick 'conflict resolution' versions out of thin air, without knowledge of the actual requested ranges. This can be dangerous because users may unwittingly pick versions that actually violate dependency constraints and may break at runtime.

### 1) Concise versions.props

With this plugin, your `versions.props` can contain only versions for things you directly depend on, you don't have to specify anything else:

```diff
 com.fasterxml.jackson.*:jackson-* = 2.9.6
 com.google.guava:guava = 21.0
 com.palantir.conjure:* = 4.0.0
 com.palantir.conjure.java:* = 1.1.0
 com.palantir.remoting3:* = 3.37.0
 junit:junit = 4.12
 org.assertj:* = 3.10.0

-# CONFLICT RESOLUTION
-org.slf4j:slf4j-api = 1.7.25
-com.palantir.ri:resource-identifier = 1.0.1
-com.palantir.remoting-api:errors = 1.8.0
```

### 2) Compact versions.lock

When you run `./gradlew --write-locks`, the plugin will automatically write a new file: `versions.lock` which contains a version for every single one of your transitive dependencies.  This is then used as the source of truth for all versions in all your projects.

Notably, this lockfile is a _compact_ representation of your dependency graph as it just has one line per dependency (unlike previous nebula lock files which spanned thousands of lines).

```
javax.annotation:javax.annotation-api:1.3.2 (3 constraints: 1a310f90)
javax.inject:javax.inject:1 (2 constraints: d614a0ab)
javax.servlet:javax.servlet-api:3.1.0 (1 constraints: 830dcc28)
javax.validation:validation-api:1.1.0.Final (3 constraints: dc393f20)
javax.ws.rs:javax.ws.rs-api:2.0.1 (8 constraints: 7e9ce067)
```

### Comparison to other languages

Many other languages have implemented this exact workflow.  Direct dependencies are specified in some top level file and then the build tool just figures out a sensible version for all the transitive dependencies and writes out the whole thing to a some lock file:

- Frontend repos using yarn define dependencies in a `package.json` and then yarn auto-generates a `yarn.lock` file.
- Go repos using dep define dependencies in a `Gopkg.toml` file and have `Gopkg.lock` auto-generated.
- Rust repos define direct dependencies in a `cargo.toml` file and have `cargo.lock` auto-generated.

### Comparison to nebula dependency lock plugin

A few projects started using Nebula's [Gradle Dependency Lock Plugin](https://github.com/nebula-plugins/gradle-dependency-lock-plugin) but eventually abandoned it because it introduced too much dev friction.  Key problems:

- PR diffs could be thousands and thousands of lines for small changes
- Contributors often updated versions.props but forgot to update the lockfiles, leading them to think they'd picked up a new version when they actually hadn't!

Both of these problems are solved by this plugin because the new gradle lock files are extremely compact (one line per dependency) and they can never get out of date because gradle validates their correctness every time you resolve a configuration.

### Comparison to manual verifyDependencyLocksAreCurrent task

http-remoting still uses lock files and defines a [custom task](https://github.com/palantir/http-remoting/blob/3.33.0/build.gradle#L69) to check they are up to date.  This is no longer necessary because lockfiles are now a first-class feature in Gradle 4.8.

```gradle
// no longer necessary
task verifyDependencyLocksAreCurrent {
    doLast {
        def expectedDependencies = tasks.saveLock.getOutputLock()
        def actualDependencies = tasks.saveLock.getGeneratedLock()
        def digester = java.security.MessageDigest.getInstance('SHA')
        logger.info("Verifying integrity of dependency locks: {} vs {}", expectedDependencies, actualDependencies)
        if (digester.digest(expectedDependencies.bytes) != digester.digest(actualDependencies.bytes)) {
            throw new GradleException("The dependencies of project " + project.name + " do not match the expected "
                + "dependencies recorded in " + expectedDependencies + ". "
                + "Run `./gradlew generateLock saveLock` and commit the updated version.lock files")
        }
    }
}
```

## More usage

By default, this plugin will apply the constraints from `versions.props` to _all_ configurations.
To exclude a configuration from receiving the constraints, you can add it to `excludeConfigurations`, configurable through the `versionRecommendations` extension (in the root project):

    versionRecommendations {
        excludeConfigurations 'zinc'
    }

To understand why a particular version in your lockfile has been chosen, run `./gradlew why --hash a60c3ce8` to expand the constraints:
```
> Task :why
com.fasterxml.jackson.core:jackson-databind:2.9.8
        com.fasterxml.jackson.module:jackson-module-jaxb-annotations -> 2.9.8
        com.netflix.feign:feign-jackson -> 2.6.4
        com.palantir.config.crypto:encrypted-config-value -> 2.6.1
        com.palantir.config.crypto:encrypted-config-value-module -> 2.6.1
```

If you need to a lower version of a dependency, use a forced version constraint e.g.:

```
dependencies {
    constraints {
        rootConfiguration('com.squareup.retrofit2:retrofit:2.4.0') { force = true }
    }
}
```

## Technical explanation

#### Are these vanilla Gradle lockfiles?

No.  We tried Gradle 4.8's [first-class lockfiles](https://docs.gradle.org/current/userguide/dependency_locking.html), but found a critical usability problem: it allowed semantic merge conflicts.  For example:

- Two PRs might change apparently unrelated dependencies - both changing different lines of gradle's `gradle/dependency-locks/x.lockfile`
- One PR merges first, so develop has a few lockfile lines updated
- Second PR has no git conflicts with develop, so it merges
- Develop is now broken due to a semantic merge conflict in the gradle lockfile!

Concrete example:

- PR1
  - before PR: com.palantir.spark.files:file-relation required jaxrs-clients 3.45.0
  - after PR: com.palantir.spark.files:file-relation doesn't pull in jaxrs-clients at all
  - unchanged: com.palantir.witchcraft:witchcraft-core required jaxrs-clients 3.43.0
  - result is: gradle's lockfile therefore picked jaxrs-clients 3.43.0
- PR2
  - before PR: com.palantir.witchcraft:witchcraft-core required remoting 3.43.0
  - after PR: com.palantir.witchcraft:witchcraft-core required remoting 3.45.0
  - result is: gradle's lockfile line for jaxrs-clients was _unchanged_ (still 3.45.0)

The badness arises when:

1. user merges PR1 onto develop
1. PR2 has no conflicts because it didn't change the jaxrs-clients 3.45.0 line that PR1 downgraded to 3.43.0.
1. user merges PR2 onto develop
1. develop is now broken, because PR2 required jaxrs-clients 3.45.0 but PR1 had downgraded it on develop to 3.43.0 and git didn't detect this conflict! (This is a semantic merge conflict)

Our format avoids these semantic merge conflicts by adding some redundant information about the dependency graph so that changes like these will result in _git conflicts_ (which prevent merging).

#### Conflict-safe lock files

Our format extends gradle's lockfiles format by writing down who wanted each component (`com.google.guava:guava`) and which version they requested:

```diff
-com.google.guava:guava:18.0
+com.google.guava:guava:18.0 (2 constraints 4a440103)
```

The hash, `4a440103`, is derived from Guava's dependents:

```
com.fasterxml.jackson.datatype:jackson-datatype-guava -> 18.0
com.github.rholder:guava-retrying -> [10.+,)
```

This ensures that if two PRs affect the set of constraints on guava they will result in a _git conflict_, which prevents the semantic merge conflicts described above and ensures develop won't break:

```
<<<<<<< HEAD:versions.lock
com.google.guava:guava:18.0 (3 constraints: f59715c4)
=======
com.google.guava:guava:10.0 (1 constraints: 50295fc1)
>>>>>>> 77976da35a11db4580b80ae27e8d65caf5208086:versions.lock
```

The hash from one side of the merge confict (`f59715c4`) came from adding a tracing dependent:
```diff
 com.fasterxml.jackson.datatype:jackson-datatype-guava -> 18.0
+com.palantir.tracing:tracing -> 16.0
 com.github.rholder:guava-retrying -> [10.+,)
```

While the other hash `50295fc1`, is derived by deleting jackson, leaving just a single dependent:
```diff
-com.fasterxml.jackson.datatype:jackson-datatype-guava -> 18.0
 com.github.rholder:guava-retrying -> [10.+,)
```

With vanilla Gradle 4.8 lockfiles, this scenario would have merged but then failed on develop due to an inconsistent lockfile.

#### Which configurations are affected?

The lockfile is sourced from the _compileClasspath_ and _runtimeClasspath_ configurations.
(Test-only dependencies will not appear in `versions.lock`.)

#### Known limitation: root project must have a unique name

This plugin requires the settings.gradle to declare a `rootProject.name` which is unique, due to a Gradle internal implementation detail.

```diff
+rootProject.name = 'foundry-sls-status'
-rootProject.name = 'sls-status'

 include 'sls-status'
 include 'sls-status-api'
 include 'sls-status-api:sls-status-api-objects'
 include 'sls-status-dropwizard'
```
