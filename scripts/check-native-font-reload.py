#!/usr/bin/env python3
"""Verify native font ownership transfers without JNI reinstallation across Java reloads."""
from pathlib import Path
import os
import subprocess
import tempfile

root = Path(__file__).resolve().parent.parent
jdk = Path(os.environ.get('JAVA_HOME', '/opt/homebrew/opt/openjdk@21')) / 'bin'
sdk = Path(os.environ.get('ANDROID_HOME', '/Users/rikumi/Library/Android/sdk'))
android = next(sdk.glob('platforms/android-37*/android.jar'))
with tempfile.TemporaryDirectory(prefix='coloros-native-reload-', dir='/tmp') as directory:
    out = Path(directory)
    stub = out / 'XposedInit.java'
    stub.write_text('''package com.rikumi.colorosmod;
public class XposedInit {
    public android.content.SharedPreferences getRemotePreferences(String name) { throw new AssertionError("Unexpected reconfiguration"); }
    public android.os.ParcelFileDescriptor openRemoteFile(String name) { throw new AssertionError("Unexpected reconfiguration"); }
    public static void log(String message) { throw new AssertionError(message); }
}
''')
    constants = out / 'SystemFontHooks.java'
    constants.write_text('package com.rikumi.colorosmod.hooks; public class SystemFontHooks { public static final String PREFS = "system_fonts"; }')
    subprocess.run([str(jdk / 'javac'), '-cp', str(android), '-d', str(out), str(stub), str(constants),
                    str(root / 'app/src/main/java/com/rikumi/colorosmod/hooks/NativeFontHooks.java'),
                    str(root / 'tests/hot-reload/NativeFontReloadTest.java')], check=True)
    subprocess.run([str(jdk / 'java'), '-cp', str(out), 'NativeFontReloadTest', str(out), str(android)], check=True)
