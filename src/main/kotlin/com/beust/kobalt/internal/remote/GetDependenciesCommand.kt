package com.beust.kobalt.internal.remote

import com.beust.kobalt.Args
import com.beust.kobalt.internal.PluginInfo
import com.beust.kobalt.kotlin.BuildFile
import com.beust.kobalt.kotlin.BuildFileCompiler
import com.beust.kobalt.maven.DependencyManager
import com.beust.kobalt.maven.IClasspathDependency
import com.beust.kobalt.maven.MavenDependency
import com.beust.kobalt.misc.KobaltExecutors
import com.beust.kobalt.misc.log
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.nio.file.Paths
import javax.inject.Inject

/**
 * This command returns the list of dependencies for the given buildFile.
 * Payload:
 * { "name" : "getDependencies", "buildFile": "/Users/beust/kotlin/kobalt/kobalt/src/Build.kt" }
 * The response is a GetDependenciesData.
 */
class GetDependenciesCommand @Inject constructor(val executors: KobaltExecutors,
        val buildFileCompilerFactory: BuildFileCompiler.IFactory, val args: Args,
        val dependencyManager: DependencyManager, val pluginInfo: PluginInfo) : ICommand {
    override val name = "getDependencies"
    override fun run(sender: ICommandSender, received: JsonObject) {
        val buildFile = BuildFile(Paths.get(received.get("buildFile").asString), "GetDependenciesCommand")
        val scriptCompiler = buildFileCompilerFactory.create(listOf(buildFile), pluginInfo)
        scriptCompiler.observable.subscribe {
            buildScriptInfo -> if (buildScriptInfo.projects.size > 0) {
                sender.sendData(toData(buildScriptInfo))
            }
        }
        scriptCompiler.compileBuildFiles(args)
    }

    private fun toData(info: BuildFileCompiler.BuildScriptInfo) : CommandData {
        val executor = executors.miscExecutor
        val projects = arrayListOf<ProjectData>()

        fun toDependencyData(d: IClasspathDependency, scope: String) : DependencyData {
            val dep = MavenDependency.create(d.id, executor)
            return DependencyData(d.id, scope, dep.jarFile.get().absolutePath)
        }

        fun allDeps(l: List<IClasspathDependency>) = dependencyManager.transitiveClosure(l)

        info.projects.forEach { project ->
            val allDependencies =
                allDeps(project.compileDependencies).map { toDependencyData(it, "compile") } +
                allDeps(project.compileProvidedDependencies).map { toDependencyData(it, "provided") } +
                allDeps(project.compileRuntimeDependencies).map { toDependencyData(it, "runtime") } +
                allDeps(project.testDependencies).map { toDependencyData(it, "testCompile") } +
                allDeps(project.testProvidedDependencies).map { toDependencyData(it, "testProvided") }

            projects.add(ProjectData(project.name, allDependencies))
        }
        log(1, "Returning BuildScriptInfo")
        val result = toCommandData(Gson().toJson(GetDependenciesData(projects)))
        log(2, "  $result")
        return result
    }

    /////
    // The JSON payloads that this command uses
    //

    class DependencyData(val id: String, val scope: String, val path: String)

    class ProjectData( val name: String, val dependencies: List<DependencyData>)

    class GetDependenciesData(val projects: List<ProjectData>)
}
