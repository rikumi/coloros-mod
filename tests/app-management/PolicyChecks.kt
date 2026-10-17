package com.rikumi.colorosmod

private var checks = 0
private fun verify(value: Boolean, message: String) { check(value) { message }; checks++ }
private fun reject(block: () -> Unit) { verify(runCatching(block).isFailure, "Expected rejection") }

fun main() {
    val gate = AppManagementEntryGate()
    (0L..3L).forEach { verify(!gate.tap(it * 100), "Fewer than five taps must stay closed") }
    verify(gate.tap(400), "Fifth tap must open warning, not issue a command")
    verify(!gate.tap(450), "Successful sequence resets counter")
    gate.reset()
    listOf(0L, 200L, 400L, 600L).forEach { verify(!gate.tap(it), "Prelude") }
    verify(!gate.tap(1600), "Gap exactly 1000 ms must reset")
    listOf(1700L, 1800L, 1900L).forEach { verify(!gate.tap(it), "Restarted sequence") }
    verify(gate.tap(2000), "Five fresh taps after timeout")
    gate.reset()
    listOf(0L, 999L, 1998L, 2997L).forEach { verify(!gate.tap(it), "Interval is per consecutive tap") }
    verify(gate.tap(3996), "Each gap below a second is accepted, even if total exceeds a second")
    gate.reset()
    repeat(4) { verify(!gate.tap(400L + it), "Prelude") }
    verify(!gate.tap(100), "Clock rollback resets counter")
    gate.reset()
    verify(!gate.tap(101), "Leaving/recreating hint resets counter")

    val pkg = "com.example.system_app"
    val a = ManagedPackage("/system/app/a/base.apk", pkg)
    val b = ManagedPackage("/system/app/b/base.apk", "com.example.removed")
    val c = ManagedPackage("/system/app/c/base.apk", "com.example.enabled")
    val parsed = AppManagementPolicy.parsePackages("package:${a.apkPath}=${a.packageName}\npackage:${b.apkPath}=${b.packageName}\n")
    verify(parsed == listOf(a, b), "Parse full -f output")
    verify(AppManagementPolicy.parsePackages("package:${a.apkPath}=$pkg\npackage:${a.apkPath}=$pkg").size == 1, "Deduplicate packages")
    verify(AppManagementPolicy.parsePackages("").isEmpty(), "Successful empty list")
    listOf("Permission denied", "package:missing-separator", "package:/system/a.apk=com.app;reboot").forEach { raw ->
        reject { AppManagementPolicy.parsePackages(raw) }
    }
    val initial = AppManagementPolicy.partition(listOf(a, b), listOf(a, b, c), listOf(a, c))
    verify(initial.disabled == listOf(a), "User-uninstalled entry must not appear twice")
    verify(initial.uninstalled == listOf(b), "Installed vs retained APK difference")
    val enabled = AppManagementPolicy.partition(emptyList(), listOf(a, b, c), listOf(a, c))
    verify(enabled.disabled.isEmpty() && enabled.uninstalled == listOf(b), "Enable refresh removes only disabled state")
    val reinstalled = AppManagementPolicy.partition(emptyList(), listOf(a, b, c), listOf(a, b, c))
    verify(reinstalled.disabled.isEmpty() && reinstalled.uninstalled.isEmpty(), "Install-existing refresh restores installed state")

    val expected = mapOf(
        ManagedAppOperation.DISABLE to "pm disable-user --user 0 $pkg",
        ManagedAppOperation.UNINSTALL to "pm uninstall -k --user 0 $pkg",
        ManagedAppOperation.ENABLE to "pm enable --user 0 $pkg",
        ManagedAppOperation.INSTALL_EXISTING to "cmd package install-existing --user 0 $pkg",
    )
    expected.forEach { (action, command) ->
        verify(action.command(pkg) == command, "Correct operation and user scope")
        listOf("", "com.app;reboot", "com.app\nreboot", "com.app/Activity", "com.app'", "--user").forEach { bad ->
            reject { action.command(bad) }
        }
        verify(!action.succeeded(pkg, "Failure [denied]"), "Failure output cannot succeed")
        verify(!action.succeeded(pkg, ""), "Empty output cannot succeed")
    }
    verify(ManagedAppOperation.DISABLE.succeeded(pkg, "Package $pkg new state: disabled-user\n"), "Disable success")
    verify(!ManagedAppOperation.DISABLE.succeeded(pkg, "Package com.other new state: disabled-user"), "Wrong package rejected")
    verify(!ManagedAppOperation.DISABLE.succeeded(pkg, "Package $pkg new state: enabled"), "Wrong state rejected")
    verify(ManagedAppOperation.ENABLE.succeeded(pkg, "Package $pkg new state: enabled"), "Enable success")
    verify(ManagedAppOperation.UNINSTALL.succeeded(pkg, "Success"), "Uninstall success")
    verify(!ManagedAppOperation.UNINSTALL.succeeded(pkg, "Success: unrelated"), "Success prefix is insufficient")
    verify(ManagedAppOperation.INSTALL_EXISTING.succeeded(pkg, "Package $pkg installed for user: 0"), "Install-existing success")
    verify(!ManagedAppOperation.INSTALL_EXISTING.succeeded(pkg, "Package $pkg installed for user: 10"), "Wrong user rejected")
    println("PASS: $checks policy checks; no Android device commands executed")
}
