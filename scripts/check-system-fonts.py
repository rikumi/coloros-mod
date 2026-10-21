#!/usr/bin/env python3
"""Exercise TTC indexing, weight/locale matching, invalid inputs and bundled font metadata."""
from pathlib import Path
import os
import subprocess
import tempfile
root = Path(__file__).resolve().parent.parent
jdk = Path(os.environ.get('JAVA_HOME', '/opt/homebrew/opt/openjdk@21')) / 'bin'
with tempfile.TemporaryDirectory(prefix='coloros-font-tests-', dir='/tmp') as output:
    subprocess.run([str(jdk / 'javac'), '-d', output,
                    str(root / 'app/src/main/java/com/rikumi/colorosmod/FontFileInfo.java'),
                    str(root / 'app/src/main/java/com/rikumi/colorosmod/FontOverlayXml.java'),
                    str(root / 'tests/system-fonts/FontFileInfoTest.java'),
                    str(root / 'tests/system-fonts/FontOverlayXmlTest.java')], check=True)
    fonts = sorted((root / 'app/src/main/assets/system_fonts').glob('*.ttf'))
    fonts += sorted((root / 'app/src/main/assets/clock_fonts').glob('*.ttf'))
    subprocess.run([str(jdk / 'java'), '-cp', output, 'FontFileInfoTest', *map(str, fonts)], check=True)

    subprocess.run([str(jdk / 'java'), '-cp', output, 'FontOverlayXmlTest'], check=True)

    import shutil
    compiler = shutil.which('clang++') or shutil.which('c++')
    subprocess.run([compiler, '-std=c++17', '-I', str(root / 'app/src/main/cpp'),
                    str(root / 'tests/system-fonts/font_paths_test.cpp'), '-o', output + '/font-paths-test'], check=True)
    subprocess.run([output + '/font-paths-test'], check=True)
