package com.rikumi.colorosmod

internal data class ManagedPackage(val apkPath: String, val packageName: String)

internal data class ManagedAppLists(
    val disabled: List<ManagedPackage>,
    val uninstalled: List<ManagedPackage>,
)

internal object AppManagementPolicy {
    const val USER_ID = 0
    private val packagePattern = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*")

    fun validPackageName(pkg: String): Boolean = packagePattern.matches(pkg)

    fun partition(disabled: List<ManagedPackage>, all: List<ManagedPackage>,
        installed: List<ManagedPackage>): ManagedAppLists {
        val installedNames = installed.map { it.packageName }.toSet()
        val uninstalled = all.filter { it.packageName !in installedNames }
        val uninstalledNames = uninstalled.map { it.packageName }.toSet()
        return ManagedAppLists(disabled.filter { it.packageName !in uninstalledNames }, uninstalled)
    }

    fun parsePackages(output: String): List<ManagedPackage> = output.lineSequence()
        .map(String::trim).filter(String::isNotEmpty).map { line ->
            val separator = line.lastIndexOf('=')
            require(line.startsWith("package:") && separator > "package:".length) { line }
            val pkg = line.substring(separator + 1)
            require(validPackageName(pkg)) { line }
            ManagedPackage(line.substring("package:".length, separator), pkg)
        }.distinctBy { it.packageName }.toList()
}

internal enum class ManagedAppOperation {
    DISABLE, UNINSTALL, ENABLE, INSTALL_EXISTING;

    fun command(pkg: String): String {
        require(AppManagementPolicy.validPackageName(pkg))
        val user = AppManagementPolicy.USER_ID
        return when (this) {
            DISABLE -> "pm disable-user --user $user $pkg"
            UNINSTALL -> "pm uninstall -k --user $user $pkg"
            ENABLE -> "pm enable --user $user $pkg"
            INSTALL_EXISTING -> "cmd package install-existing --user $user $pkg"
        }
    }

    fun succeeded(pkg: String, output: String): Boolean {
        require(AppManagementPolicy.validPackageName(pkg))
        val expected = when (this) {
            DISABLE -> "Package $pkg new state: disabled-user"
            UNINSTALL -> "Success"
            ENABLE -> "Package $pkg new state: enabled"
            INSTALL_EXISTING -> "Package $pkg installed for user: ${AppManagementPolicy.USER_ID}"
        }
        return output.lineSequence().any { it.trim() == expected }
    }
}
