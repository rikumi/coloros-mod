#!/usr/bin/env python3
"""Run pure Kotlin policy simulations and locale checks; never builds the APK or invokes su/adb."""
from pathlib import Path
import os
import re
import subprocess
import tempfile
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
resources = root / 'app/src/main/res'
reference = None
for locale in ['values', 'values-en', 'values-zh-rHK', 'values-zh-rTW']:
    entries = ET.parse(resources / locale / 'app_management_strings.xml').getroot()
    values = {entry.attrib['name']: entry.text or '' for entry in entries}
    assert len(values) == len(entries), f'Duplicate keys: {locale}'
    if reference is None:
        reference = values
    assert values.keys() == reference.keys(), f'Missing translations: {locale}'
    for key, value in values.items():
        assert re.findall(r'%\d+\$[a-z]', value) == re.findall(r'%\d+\$[a-z]', reference[key]), (locale, key)
        assert value.count(r'\n') == reference[key].count(r'\n'), (locale, key)

sources = root / 'app/src/main/java/com/rikumi/colorosmod'
ui = (sources / 'DisabledAppsScreen.kt').read_text()
commands = (sources / 'ManagedAppsCommands.kt').read_text()
used = set(re.findall(r'R\.string\.(app_management_\w+)', ui + commands))
assert used == reference.keys(), (used - reference.keys(), reference.keys() - used)
assert 'var picking by remember { mutableStateOf(false) }' in ui
assert 'if (index == 0) onAdd()' in ui
assert 'pm list packages -f -s --user ${AppManagementPolicy.USER_ID}' in commands
assert 'ui_disabled_apps_hint' in ui
print(f'PASS: {len(reference)} strings in all 4 locales; actual UI/backend references and hidden-entry wiring')

# Use existing local compiler dependencies, isolated output; no Gradle or Android toolchain.
cache = Path.home() / '.gradle/caches/modules-2/files-2.1'
def jar(path):
    matches = sorted((cache / path).rglob('*.jar'))
    if not matches:
        raise SystemExit(f'Missing cached dependency: {path}')
    return matches[0]
compiler = jar('org.jetbrains.kotlin/kotlin-compiler-embeddable/2.0.20')
stdlib = jar('org.jetbrains.kotlin/kotlin-stdlib/2.0.20')
trove = jar('org.jetbrains.intellij.deps/trove4j')
coroutines = jar('org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm')
annotations = jar('org.jetbrains/annotations')
java = Path(os.environ.get('JAVA_HOME', '/opt/homebrew/opt/openjdk@21')) / 'bin/java'
classpath = os.pathsep.join(map(str, [compiler, stdlib, trove, coroutines, annotations]))
with tempfile.TemporaryDirectory(prefix='coloros-app-management-') as output:
    subprocess.run([str(java), '-Djava.io.tmpdir=' + output, '-cp', classpath,
                    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
                    '-classpath', str(stdlib), '-d', output,
                    str(sources / 'AppManagementPolicy.kt'),
                    str(root / 'tests/app-management/PolicyChecks.kt')], check=True)
    subprocess.run([str(java), '-cp', output + os.pathsep + str(stdlib),
                    'com.rikumi.colorosmod.PolicyChecksKt'], check=True)
