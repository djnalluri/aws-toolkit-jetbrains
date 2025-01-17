// Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

import com.jetbrains.rd.generator.gradle.RdGenExtension
import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.DetektCreateBaselineTask
import org.jetbrains.intellij.tasks.PrepareSandboxTask
import software.aws.toolkits.gradle.intellij.IdeFlavor
import software.aws.toolkits.gradle.intellij.IdeVersions
import java.nio.file.Path

buildscript {
    // Cannot be removed or else it will fail to compile
    @Suppress("RemoveRedundantQualifierName")
    val rdversion = software.aws.toolkits.gradle.intellij.IdeVersions.ideProfile(project).rider.rdGenVersion

    println("Using rd-gen: $rdversion")

    repositories {
        maven("https://www.myget.org/F/rd-snapshots/maven/")
        mavenCentral()
    }

    dependencies {
        classpath("com.jetbrains.rd:rd-gen:$rdversion")
    }
}

val ideProfile = IdeVersions.ideProfile(project)

plugins {
    id("toolkit-kotlin-conventions")
    id("toolkit-testing")
    id("toolkit-integration-testing")
    id("toolkit-intellij-subplugin")
}

intellijToolkit {
    ideFlavor.set(IdeFlavor.RD)
}

intellij {
    type.set("RD")
}

sourceSets {
    main {
        java.srcDirs("$buildDir/generated-src")
    }
}

dependencies {
    implementation(project(":jetbrains-core", "instrumentedJar"))
    testImplementation(project(path = ":jetbrains-core", configuration = "testArtifacts"))
}

/**
 * RESHARPER
 */

// Not published to gradle plugin portal, use old syntax
// TODO: rdgen 2023.1.2 doesn't work with gradle 8.0
// apply(plugin = "com.jetbrains.rdgen")
class RdGenPlugin2 : Plugin<Project> {
    override fun apply(project: Project) {
        project.extensions.create("rdgen", RdGenExtension::class.java, project)
        project.configurations.create("rdGenConfiguration")
        project.tasks.create("rdgen", RdGenTask2::class.java)

        project.dependencies.run {
            add("rdGenConfiguration", "org.jetbrains.kotlin:kotlin-compiler-embeddable:1.7.0")
            add("rdGenConfiguration", "org.jetbrains.kotlin:kotlin-stdlib:1.7.0")
            add("rdGenConfiguration", "org.jetbrains.kotlin:kotlin-reflect:1.7.0")
            add("rdGenConfiguration", "org.jetbrains.kotlin:kotlin-stdlib-common:1.7.0")
            add("rdGenConfiguration", "org.jetbrains.intellij.deps:trove4j:1.0.20181211")
        }
    }
}

open class RdGenTask2 : JavaExec() {
    private val local = extensions.create("params", RdGenExtension::class.java, this)
    private val global = project.extensions.findByType(RdGenExtension::class.java)

    fun rdGenOptions(action: (RdGenExtension) -> Unit) {
        local.apply(action)
    }

    override fun exec() {
        args(generateArgs())

        val files = project.configurations.getByName("rdGenConfiguration").files
        val buildScriptFiles = project.buildscript.configurations.getByName("classpath").files
        val rdFiles: MutableSet<File> = HashSet()
        for (file in buildScriptFiles) {
            if (file.name.contains("rd-")) {
                rdFiles.add(file)
            }
        }
        classpath(files)
        classpath(rdFiles)
        super.exec()
    }

    private fun generateArgs(): List<String?> {
        val effective = local.mergeWith(global!!)
        return effective.toArguments()
    }

    init {
        mainClass.set("com.jetbrains.rd.generator.nova.MainKt")
    }
}
apply<RdGenPlugin2>()

val resharperPluginPath = File(projectDir, "ReSharper.AWS")
val resharperBuildPath = File(project.buildDir, "dotnetBuild")

val resharperParts = listOf(
    "AWS.Daemon",
    "AWS.Localization",
    "AWS.Project",
    "AWS.Psi",
    "AWS.Settings"
)

val buildConfiguration = project.extra.properties["BuildConfiguration"] ?: "Debug" // TODO: Do we ever want to make a release build?

// Protocol
val protocolGroup = "protocol"

val csDaemonGeneratedOutput = File(resharperPluginPath, "src/AWS.Daemon/Protocol")
val csPsiGeneratedOutput = File(resharperPluginPath, "src/AWS.Psi/Protocol")
val csAwsSettingsGeneratedOutput = File(resharperPluginPath, "src/AWS.Settings/Protocol")
val csAwsProjectGeneratedOutput = File(resharperPluginPath, "src/AWS.Project/Protocol")

val riderGeneratedSources = File("$buildDir/generated-src/software/aws/toolkits/jetbrains/protocol")

val modelDir = File(projectDir, "protocol/model")
val rdgenDir = File("${project.buildDir}/rdgen/")

rdgenDir.mkdirs()

configure<RdGenExtension> {
    verbose = true
    hashFolder = rdgenDir.toString()

    classpath({
        val ijDependency = tasks.setupDependencies.flatMap { it.idea }.map { it.classes }.get()
        println("Calculating classpath for rdgen, intellij.ideaDependency is: $ijDependency")
        File(ijDependency, "lib/rd").resolve("rider-model.jar").absolutePath
    })

    sources(projectDir.resolve("protocol/model"))
    packages = "model"
}

// TODO: migrate to official rdgen gradle plugin https://www.jetbrains.com/help/resharper/sdk/Rider.html#plugin-project-jvm
val generateModels = tasks.register<RdGenTask2>("generateModels") {
    group = protocolGroup
    description = "Generates protocol models"

    inputs.dir(file("protocol/model"))

    outputs.dir(riderGeneratedSources)
    outputs.dir(csDaemonGeneratedOutput)
    outputs.dir(csPsiGeneratedOutput)
    outputs.dir(csAwsSettingsGeneratedOutput)
    outputs.dir(csAwsProjectGeneratedOutput)

    systemProperty("ktDaemonGeneratedOutput", riderGeneratedSources.resolve("DaemonProtocol").absolutePath)
    systemProperty("csDaemonGeneratedOutput", csDaemonGeneratedOutput.absolutePath)

    systemProperty("ktPsiGeneratedOutput", riderGeneratedSources.resolve("PsiProtocol").absolutePath)
    systemProperty("csPsiGeneratedOutput", csPsiGeneratedOutput.absolutePath)

    systemProperty("ktAwsSettingsGeneratedOutput", riderGeneratedSources.resolve("AwsSettingsProtocol").absolutePath)
    systemProperty("csAwsSettingsGeneratedOutput", csAwsSettingsGeneratedOutput.absolutePath)

    systemProperty("ktAwsProjectGeneratedOutput", riderGeneratedSources.resolve("AwsProjectProtocol").absolutePath)
    systemProperty("csAwsProjectGeneratedOutput", csAwsProjectGeneratedOutput.absolutePath)
}

val cleanGenerateModels = tasks.register<Delete>("cleanGenerateModels") {
    group = protocolGroup
    description = "Clean up generated protocol models"

    delete(generateModels)
}

// Backend
val backendGroup = "backend"
val codeArtifactNugetUrl: Provider<String> = providers.environmentVariable("CODEARTIFACT_NUGET_URL")
val prepareBuildProps = tasks.register("prepareBuildProps") {
    val riderSdkVersionPropsPath = File(resharperPluginPath, "RiderSdkPackageVersion.props")
    group = backendGroup

    inputs.property("riderNugetSdkVersion", ideProfile.rider.nugetVersion)
    outputs.file(riderSdkVersionPropsPath)

    doLast {
        val netFrameworkTarget = ideProfile.rider.netFrameworkTarget
        val riderSdkVersion = ideProfile.rider.nugetVersion
        val configText = """<Project>
  <PropertyGroup>
    <NetFrameworkTarget>$netFrameworkTarget</NetFrameworkTarget>
    <RiderSDKVersion>[$riderSdkVersion]</RiderSDKVersion>
    <DefineConstants>PROFILE_${ideProfile.name.replace(".", "_")}</DefineConstants>
  </PropertyGroup>
</Project>
"""
        riderSdkVersionPropsPath.writeText(configText)
    }
}

val prepareNuGetConfig = tasks.register("prepareNuGetConfig") {
    group = backendGroup

    dependsOn(tasks.setupDependencies)

    val nugetConfigPath = File(projectDir, "NuGet.Config")
    // FIX_WHEN_MIN_IS_211 remove the projectDir one above
    val nugetConfigPath211 = Path.of(projectDir.absolutePath, "testData", "NuGet.config").toFile()

    inputs.property("rdVersion", ideProfile.rider.sdkVersion)
    outputs.files(nugetConfigPath, nugetConfigPath211)

    doLast {
        val nugetPath = getNugetPackagesPath()
        val codeArtifactConfigText = """<?xml version="1.0" encoding="utf-8"?>
  <configuration>
    <packageSources> 
    ${
        if (codeArtifactNugetUrl.isPresent) {
            """
       |   <clear /> 
       |   <add key="codeartifact-nuget" value="${codeArtifactNugetUrl.get()}v3/index.json" />
        """.trimMargin("|")
        } else {
            ""
        }
        }
    </packageSources>
  </configuration>
"""
        val configText = """<?xml version="1.0" encoding="utf-8"?>
<configuration>
  <packageSources>
    <add key="resharper-sdk" value="$nugetPath" />
  </packageSources>
</configuration>
"""
        nugetConfigPath.writeText(codeArtifactConfigText)
        nugetConfigPath211.writeText(configText)
    }
}

val buildReSharperPlugin = tasks.register("buildReSharperPlugin") {
    group = backendGroup
    description = "Builds the full ReSharper backend plugin solution"
    dependsOn(generateModels, prepareBuildProps, prepareNuGetConfig)

    inputs.dir(resharperPluginPath)
    outputs.dir(resharperBuildPath)

    doLast {
        val arguments = listOf(
            "build",
            "--verbosity",
            "normal",
            "${resharperPluginPath.canonicalPath}/ReSharper.AWS.sln"
        )
        exec {
            executable = "dotnet"
            args = arguments
        }
    }
}

fun getNugetPackagesPath(): File {
    val sdkPath = tasks.setupDependencies.flatMap { it.idea }.map { it.classes }.get()
    println("SDK path: $sdkPath")

    val riderSdk = File(sdkPath, "lib/DotNetSdkForRdPlugins")

    println("NuGet packages: $riderSdk")
    if (!riderSdk.isDirectory) throw IllegalStateException("$riderSdk does not exist or not a directory")

    return riderSdk
}

val resharperDlls = configurations.create("resharperDlls") {
    isCanBeResolved = false
}

val resharperDllsDir = tasks.register<Sync>("resharperDllsDir") {
    from(buildReSharperPlugin) {
        include("**/bin/**/$buildConfiguration/**/AWS*.dll")
        include("**/bin/**/$buildConfiguration/**/AWS*.pdb")
        // TODO: see if there is better way to do this
        exclude("**/AWSSDK*")
    }
    into("$buildDir/$name")

    includeEmptyDirs = false

    eachFile {
        path = name // Clear out the path to flatten it
    }

    // TODO how is this being called twice? Can we fix it?
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}

artifacts {
    add(resharperDlls.name, resharperDllsDir)
}

val cleanNetBuilds = tasks.register<Delete>("cleanNetBuilds") {
    group = protocolGroup
    description = "Clean up obj/ bin/ folders under ReSharper.AWS"
    delete(resharperBuildPath)
}

tasks.clean {
    dependsOn(cleanGenerateModels, cleanNetBuilds)
}

// Tasks:
//
// `buildPlugin` depends on `prepareSandbox` task and then zips up the sandbox dir and puts the file in rider/build/distributions
// `runIde` depends on `prepareSandbox` task and then executes IJ inside the sandbox dir
// `prepareSandbox` depends on the standard Java `jar` and then copies everything into the sandbox dir

tasks.withType<PrepareSandboxTask>().all {
    dependsOn(resharperDllsDir)

    from(resharperDllsDir) {
        into("aws-toolkit-jetbrains/dotnet")
    }
}

tasks.compileKotlin {
    dependsOn(generateModels)
}

tasks.withType<Detekt>() {
    // Make sure kotlin code is generated before we execute detekt
    dependsOn(generateModels)
}

tasks.test {
    useTestNG()
    environment("LOCAL_ENV_RUN", true)
    maxHeapSize = "1024m"
}

tasks.integrationTest {
    useTestNG()
    environment("LOCAL_ENV_RUN", true)
    maxHeapSize = "1024m"

    // test detection is broken for tests inheriting from JB test framework: https://youtrack.jetbrains.com/issue/IDEA-278926
    setScanForTestClasses(false)
    include("**/*Test.class")
}

// fix implicit dependency on generated source
tasks.withType<DetektCreateBaselineTask>() {
    dependsOn(generateModels)
}

// weird implicit dependency issue with how we run windows tests
tasks.named("classpathIndexCleanup") {
    dependsOn(tasks.named("compileIntegrationTestKotlin"))
}
