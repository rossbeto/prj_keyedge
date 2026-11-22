import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

/** Access to gradle/libs.versions.toml from precompiled convention plugins. */
val Project.catalog: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun VersionCatalog.v(name: String): String = findVersion(name).get().requiredVersion

fun VersionCatalog.lib(name: String) = findLibrary(name).get()
