/*
 * Copyright 2010-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.jvm.compiler

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.DelegatingGlobalSearchScope
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.analyzer.*
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.builtins.jvm.JvmBuiltIns
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.config.LanguageVersionSettingsImpl
import org.jetbrains.kotlin.context.ProjectContext
import org.jetbrains.kotlin.context.withModule
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.impl.ModuleDescriptorImpl
import org.jetbrains.kotlin.incremental.components.NoLookupLocation
import org.jetbrains.kotlin.load.kotlin.PackagePartProvider
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.platform.TargetPlatform
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.resolve.PlatformDependentAnalyzerServices
import org.jetbrains.kotlin.resolve.CompilerEnvironment
import org.jetbrains.kotlin.resolve.constants.EnumValue
import org.jetbrains.kotlin.resolve.descriptorUtil.annotationClass
import org.jetbrains.kotlin.resolve.descriptorUtil.module
import org.jetbrains.kotlin.resolve.jvm.JvmPlatformParameters
import org.jetbrains.kotlin.resolve.jvm.JvmResolverForModuleFactory
import org.jetbrains.kotlin.resolve.jvm.platform.JvmPlatformAnalyzerServices
import org.jetbrains.kotlin.test.ConfigurationKind
import org.jetbrains.kotlin.test.KotlinTestUtils
import org.jetbrains.kotlin.test.TestJdkKind
import org.jetbrains.kotlin.test.testFramework.KtUsefulTestCase
import org.jetbrains.kotlin.types.ErrorUtils
import org.junit.Assert
import java.io.File
import java.util.*

class MultiModuleJavaAnalysisCustomTest : KtUsefulTestCase() {
    private class TestModule(
        val project: Project,
        val _name: String, val kotlinFiles: List<KtFile>, val javaFilesScope: GlobalSearchScope,
        val _dependencies: TestModule.() -> List<TestModule>
    ) : ModuleInfo {
        override fun dependencies() = _dependencies()
        override val name = Name.special("<$_name>")

        override val platform: TargetPlatform
            get() = JvmPlatforms.unspecifiedJvmPlatform

        override val analyzerServices: PlatformDependentAnalyzerServices
            get() = JvmPlatformAnalyzerServices
    }

    private object BuiltInModule : ModuleInfo {
        override fun dependencies() = listOf(this)
        override val name = Name.special("<built-in module>")

        override val platform: TargetPlatform
            get() = JvmPlatforms.unspecifiedJvmPlatform

        override val analyzerServices: PlatformDependentAnalyzerServices
            get() = JvmPlatformAnalyzerServices
    }

    fun testJavaEntitiesBelongToCorrectModule() {
        val moduleDirs = File(PATH_TO_TEST_ROOT_DIR).listFiles { it -> it.isDirectory }!!
        val environment = createEnvironment(moduleDirs)
        val modules = setupModules(environment, moduleDirs)
        val projectContext = ProjectContext(environment.project, "MultiModuleJavaAnalysisTest")
        val builtIns = JvmBuiltIns(projectContext.storageManager, JvmBuiltIns.Kind.FROM_DEPENDENCIES)
        val platformParameters = JvmPlatformParameters(
            packagePartProviderFactory = { PackagePartProvider.Empty },
            moduleByJavaClass = { javaClass ->
                val moduleName = javaClass.name.asString().toLowerCase().first().toString()
                modules.first { it._name == moduleName }
            },
            isModuleAnStdlib = { false }
        )

        val resolverForProject = object : AbstractResolverForProject<ModuleInfo>(
            "test",
            projectContext,
            modules + BuiltInModule,
        ) {
            override fun sdkDependency(module: ModuleInfo): ModuleInfo? = null

            override fun modulesContent(module: ModuleInfo): ModuleContent<ModuleInfo> {
                return if (module is TestModule)
                    ModuleContent(module, module.kotlinFiles, module.javaFilesScope)
                else {
                    val allJavaFiles = GlobalSearchScope.union(modules.map(TestModule::javaFilesScope).toTypedArray())
                    ModuleContent(module, emptyList(), GlobalSearchScope.notScope(allJavaFiles))
                }
            }

            override fun builtInsForModule(module: ModuleInfo): KotlinBuiltIns = builtIns

            override fun createResolverForModule(descriptor: ModuleDescriptor, moduleInfo: ModuleInfo): ResolverForModule =
                JvmResolverForModuleFactory(
                    platformParameters,
                    CompilerEnvironment,
                    JvmPlatforms.defaultJvmPlatform
                ).createResolverForModule(
                    descriptor as ModuleDescriptorImpl,
                    projectContext.withModule(descriptor),
                    modulesContent(moduleInfo),
                    this,
                    LanguageVersionSettingsImpl.DEFAULT
                )
        }

        builtIns.builtInsModule = resolverForProject.descriptorForModule(BuiltInModule)
        builtIns.initialize(builtIns.builtInsModule, true)

        performChecks(resolverForProject, modules)
    }

    private fun createEnvironment(moduleDirs: Array<File>): KotlinCoreEnvironment {
        val configuration =
                KotlinTestUtils.newConfiguration(ConfigurationKind.JDK_ONLY, TestJdkKind.MOCK_JDK, emptyList(), moduleDirs.toList())
        return KotlinCoreEnvironment.createForTests(testRootDisposable, configuration, EnvironmentConfigFiles.JVM_CONFIG_FILES)
    }

    private fun setupModules(environment: KotlinCoreEnvironment, moduleDirs: Array<File>): List<TestModule> {
        val project = environment.project
        val modules = HashMap<String, TestModule>()
        for (dir in moduleDirs) {
            val name = dir.name
            val kotlinFiles = KotlinTestUtils.loadToJetFiles(environment, dir.listFiles { it -> it.extension == "kt" }?.toList().orEmpty())
            val javaFilesScope = object : DelegatingGlobalSearchScope(GlobalSearchScope.allScope(project)) {
                override fun contains(file: VirtualFile): Boolean {
                    if (file !in myBaseScope!!) return false
                    if (file.isDirectory) return true
                    return file.parent!!.parent!!.name == name
                }
            }
            modules[name] = TestModule(project, name, kotlinFiles, javaFilesScope) {
                when (this._name) {
                    "a" -> listOf(this)
                    "b" -> listOf(this, modules["a"]!!)
                    "c" -> listOf(this, modules["b"]!!, modules["a"]!!)
                    else -> throw IllegalStateException(_name)
                }
            }
        }
        return modules.values.toList()
    }

    private fun performChecks(resolverForProject: ResolverForProject<ModuleInfo>, modules: List<TestModule>) {
        modules.forEach {
            module ->
            val moduleDescriptor = resolverForProject.descriptorForModule(module)

            checkClassInPackage(moduleDescriptor, "test", "Kotlin${module._name.toUpperCase()}")
            checkClassInPackage(moduleDescriptor, "custom", "${module._name.toUpperCase()}Class")
        }
    }

    private fun checkClassInPackage(moduleDescriptor: ModuleDescriptor, packageName: String, className: String) {
        val kotlinPackage = moduleDescriptor.getPackage(FqName(packageName))
        val kotlinClassName = Name.identifier(className)
        val kotlinClass = kotlinPackage.memberScope.getContributedClassifier(kotlinClassName, NoLookupLocation.FROM_TEST) as ClassDescriptor
        checkClass(kotlinClass)
    }

    private fun checkClass(classDescriptor: ClassDescriptor) {
        classDescriptor.defaultType.memberScope.getContributedDescriptors().filterIsInstance<CallableDescriptor>().forEach {
            checkCallable(it)
        }

        checkSupertypes(classDescriptor)
    }

    private fun checkCallable(callable: CallableDescriptor) {
        val name = callable.name.asString()
        if (name in setOf("equals", "hashCode", "toString")) return

        val returnType = callable.returnType!!
        if (!KotlinBuiltIns.isUnit(returnType)) {
            checkDescriptor(returnType.constructor.declarationDescriptor!!, callable)
        }

        callable.valueParameters.map {
            it.type.constructor.declarationDescriptor!!
        }.forEach { checkDescriptor(it, callable) }

        callable.annotations.forEach {
            val annotationClassDescriptor = it.annotationClass!!
            checkDescriptor(annotationClassDescriptor, callable)

            Assert.assertEquals(
                    "Annotation value arguments number is not equal to number of parameters in $callable",
                    annotationClassDescriptor.constructors.single().valueParameters.size, it.allValueArguments.size)

            it.allValueArguments.forEach {
                val argument = it.value
                if (argument is EnumValue) {
                    Assert.assertEquals("Enum entry name should be <module-name>X", "X", argument.enumEntryName.identifier.last().toString())
                }
            }
        }
    }

    private fun checkSupertypes(classDescriptor: ClassDescriptor) {
        classDescriptor.defaultType.constructor.supertypes.filter {
            !KotlinBuiltIns.isAnyOrNullableAny(it)
        }.map {
            it.constructor.declarationDescriptor!!
        }.forEach {
            checkDescriptor(it, classDescriptor)
        }
    }

    private fun checkDescriptor(referencedDescriptor: ClassifierDescriptor, context: DeclarationDescriptor) {
        assert(!ErrorUtils.isError(referencedDescriptor)) { "Error descriptor: $referencedDescriptor" }

        val descriptorName = referencedDescriptor.name.asString()
        val expectedModuleName = "<${descriptorName.toLowerCase().first()}>"
        val moduleName = referencedDescriptor.module.name.asString()
        Assert.assertEquals(
                "Java class $descriptorName in $context should be in module $expectedModuleName, but instead was in $moduleName",
                expectedModuleName, moduleName
        )
    }

    companion object {
        val PATH_TO_TEST_ROOT_DIR = "compiler/testData/multiModule/java/custom"
    }
}
