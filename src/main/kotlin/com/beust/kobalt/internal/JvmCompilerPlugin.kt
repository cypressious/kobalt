package com.beust.kobalt.internal

import com.beust.kobalt.TaskResult
import com.beust.kobalt.api.BasePlugin
import com.beust.kobalt.api.IProjectContributor
import com.beust.kobalt.api.KobaltContext
import com.beust.kobalt.api.Project
import com.beust.kobalt.api.annotation.ExportedProjectProperty
import com.beust.kobalt.api.annotation.Task
import com.beust.kobalt.maven.*
import com.beust.kobalt.misc.KFiles
import com.beust.kobalt.misc.KobaltExecutors
import com.beust.kobalt.misc.log
import com.beust.kobalt.misc.warn
import com.beust.kobalt.plugin.java.JavaPlugin
import java.io.File
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
abstract class JvmCompilerPlugin @Inject constructor(
        open val localRepo: LocalRepo,
        open val files: KFiles,
        open val depFactory: DepFactory,
        open val dependencyManager: DependencyManager,
        open val executors: KobaltExecutors,
        open val jvmCompiler: JvmCompiler) : BasePlugin(), IProjectContributor {

    companion object {
        @ExportedProjectProperty(doc = "Projects this project depends on", type = "List<ProjectDescription>")
        const val DEPENDENT_PROJECTS = "dependentProjects"

        @ExportedProjectProperty(doc = "Compiler args", type = "List<String>")
        const val COMPILER_ARGS = "compilerArgs"

        const val TASK_CLEAN = "clean"
        const val TASK_TEST = "test"

        const val SOURCE_SET_MAIN = "main"
        const val SOURCE_SET_TEST = "test"
        const val DOCS_DIRECTORY = "docs/javadoc"
    }

    /**
     * Log with a project.
     */
    protected fun lp(project: Project, s: String) {
        log(2, "${project.name}: $s")
    }

    override fun apply(project: Project, context: KobaltContext) {
        super.apply(project, context)
        project.projectProperties.put(DEPENDENT_PROJECTS, projects())
        addVariantTasks(project, "compile", runTask = { taskCompile(project) })
    }

    /**
     * @return the test dependencies for this project, including the contributors.
     */
    protected fun testDependencies(project: Project) : List<IClasspathDependency> {
        val result = arrayListOf<IClasspathDependency>()
        result.add(FileDependency(makeOutputDir(project).absolutePath))
        result.add(FileDependency(makeOutputTestDir(project).absolutePath))
        with(project) {
            arrayListOf(compileDependencies, compileProvidedDependencies, testDependencies,
                    testProvidedDependencies).forEach {
                result.addAll(dependencyManager.calculateDependencies(project, context, projects(), it))
            }
        }
        val result2 = dependencyManager.reorderDependencies(result)
        return result2
    }

    @Task(name = TASK_TEST, description = "Run the tests", runAfter = arrayOf("compile", "compileTest"))
    fun taskTest(project: Project) : TaskResult {
        lp(project, "Running tests")
        val success =
            if (project.testDependencies.any { it.id.contains("testng")} ) {
                TestNgRunner(project, testDependencies(project)).runTests()
            } else {
                JUnitRunner(project, testDependencies(project)).runTests()
            }
        return TaskResult(success)
    }

    @Task(name = TASK_CLEAN, description = "Clean the project", runBefore = arrayOf("compile"))
    fun taskClean(project : Project ) : TaskResult {
        java.io.File(project.directory, project.buildDirectory).let { dir ->
            if (! dir.deleteRecursively()) {
                warn("Couldn't delete $dir")
            }
        }
        return TaskResult()
    }

    protected fun makeOutputDir(project: Project) : File = makeDir(project, KFiles.CLASSES_DIR)

    protected fun makeOutputTestDir(project: Project) : File = makeDir(project, KFiles.TEST_CLASSES_DIR)

    private fun makeDir(project: Project, suffix: String) : File {
        return File(project.directory, project.buildDirectory + File.separator + suffix).apply { mkdirs() }
    }

    /**
     * Copy the resources from a source directory to the build one
     */
    protected fun copyResources(project: Project, sourceSet: String) {
        val sourceDirs: ArrayList<String> = arrayListOf()
        var outputDir: String?
        if (sourceSet == JvmCompilerPlugin.SOURCE_SET_MAIN) {
            sourceDirs.addAll(project.sourceDirectories.filter { it.contains("resources") })
            outputDir = KFiles.CLASSES_DIR
        } else if (sourceSet == JvmCompilerPlugin.SOURCE_SET_TEST) {
            sourceDirs.addAll(project.sourceDirectoriesTest.filter { it.contains("resources") })
            outputDir = KFiles.TEST_CLASSES_DIR
        } else {
            throw IllegalArgumentException("Custom source sets not supported yet: $sourceSet")
        }

        if (sourceDirs.size > 0) {
            lp(project, "Copying $sourceSet resources")
            val absOutputDir = File(KFiles.joinDir(project.directory, project.buildDirectory!!, outputDir))
            sourceDirs.map { File(project.directory, it) }.filter {
                it.exists()
            } .forEach {
                log(2, "Copying from $sourceDirs to $absOutputDir")
                KFiles.copyRecursively(it, absOutputDir)
            }
        } else {
            lp(project, "No resources to copy for $sourceSet")
        }
    }

    private val compilerArgs = hashMapOf<String, List<String>>()

    protected fun compilerArgsFor(project: Project) : List<String> {
        val result = project.projectProperties.get(COMPILER_ARGS)
        if (result != null) {
            return result as List<String>
        } else {
            return emptyList()
        }
    }

    fun addCompilerArgs(project: Project, vararg args: String) {
        project.projectProperties.put(COMPILER_ARGS, arrayListOf(*args))
    }

    fun findSourceFiles(dir: String, sourceDirectories: Collection<String>): List<String> {
        val projectDir = File(dir)
        return files.findRecursively(projectDir,
                sourceDirectories.map { File(it) }) { it: String -> it.endsWith(".java") }
                .map { File(projectDir, it).absolutePath }
    }

    override fun projects() = projects

    @Task(name = JavaPlugin.TASK_COMPILE, description = "Compile the project")
    fun taskCompile(project: Project) : TaskResult {
        context.variant.maybeGenerateBuildConfig(project, context)
        return doCompile(project, createCompilerActionInfo(project, context))
    }

    @Task(name = JavaPlugin.TASK_JAVADOC, description = "Run Javadoc")
    fun taskJavadoc(project: Project) = doJavadoc(project, createCompilerActionInfo(project, context))

    private fun createCompilerActionInfo(project: Project, context: KobaltContext) : CompilerActionInfo {
        copyResources(project, JvmCompilerPlugin.SOURCE_SET_MAIN)

        val classpath = dependencyManager.calculateDependencies(project, context, projects,
                project.compileDependencies)

        val projectDirectory = File(project.directory)
        val buildDirectory = File(project.classesDir(context))
        buildDirectory.mkdirs()

        val initialSourceDirectories = context.variant.sourceDirectories(project)
        val sourceDirectories = context.pluginInfo.sourceDirectoriesInterceptors.fold(initialSourceDirectories,
                { sd, interceptor -> interceptor.intercept(project, context, sd) })

        val sourceFiles = files.findRecursively(projectDirectory, sourceDirectories,
                { it .endsWith(project.sourceSuffix) })
                .map { File(projectDirectory, it).path }

        val initialActionInfo = CompilerActionInfo(projectDirectory.path, classpath, sourceFiles, buildDirectory,
                emptyList())
        val result = context.pluginInfo.compilerInterceptors.fold(initialActionInfo, { ai, interceptor ->
            interceptor.intercept(project, context, ai)
        })
        return result
    }

    abstract fun doCompile(project: Project, cai: CompilerActionInfo) : TaskResult
    abstract fun doJavadoc(project: Project, cai: CompilerActionInfo) : TaskResult
}

