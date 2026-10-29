import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.tasks.AbstractJarsFlattenTask
import java.nio.file.FileSystems
import java.nio.file.Files
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(project(":shared"))

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)

    implementation(libs.compose.uiToolingPreview)
}

val appVersion: String by rootProject.extra

compose.desktop {
    application {
        mainClass = "de.ronnyporsch.voidcast.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "de.ronnyporsch.voidcast"
            // Installers only accept <major>.<minor>.<patch>
            packageVersion = appVersion.substringBefore('-')
            // From suggestRuntimeModules; the bundled JRE only contains these plus the Compose defaults
            modules("java.instrument", "java.management", "java.prefs", "jdk.security.auth", "jdk.unsupported")

            linux { iconFile.set(project.file("icons/icon.png")) }
            windows { iconFile.set(project.file("icons/icon.ico")) }
        }
    }
}

// The uber jar keeps only the first copy of each entry, so of service registrations (META-INF/services)
// that several jars make only one would survive. Merge them.
tasks.withType<AbstractJarsFlattenTask>().configureEach {
    doLast {
        val services = linkedMapOf<String, LinkedHashSet<String>>()
        fun collect(name: String, text: String) {
            services.getOrPut(name) { linkedSetOf() } += text.lines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }
        }
        inputFiles.files.forEach { file ->
            when {
                file.isDirectory -> file.resolve("META-INF/services").listFiles()?.filter { it.isFile }
                    ?.forEach { collect("META-INF/services/${it.name}", it.readText()) }
                file.name.endsWith(".jar") -> ZipFile(file).use { zip ->
                    zip.entries().asSequence()
                        .filter { !it.isDirectory && it.name.startsWith("META-INF/services/") }
                        .forEach { entry -> collect(entry.name, zip.getInputStream(entry).reader().readText()) }
                }
            }
        }
        FileSystems.newFileSystem(flattenedJar.get().asFile.toPath()).use { jar ->
            services.forEach { (name, providers) ->
                Files.write(jar.getPath(name), providers.joinToString("\n", postfix = "\n").toByteArray())
            }
        }
    }
}