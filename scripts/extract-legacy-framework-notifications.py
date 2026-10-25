#!/usr/bin/env python3
"""Bundle original Android 15 notification binary XML, IDs and default dimension values."""
import argparse
import hashlib
import re
import struct
import subprocess
import zipfile
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('apk', type=Path)
parser.add_argument('--aapt2', default='/Users/rikumi/Library/Android/sdk/build-tools/36.0.0/aapt2')
parser.add_argument('--output', type=Path, default=Path('app/src/main/assets/legacy_framework_notifications_15.zip'))
args = parser.parse_args()
dump = subprocess.run([args.aapt2, 'dump', 'resources', str(args.apk)], capture_output=True, text=True, check=True).stdout
names = {int(m[1], 16): (m[2], m[3]) for m in re.finditer(r'^\s+resource (0x[0-9a-f]+) ([^/\s]+)/([^\s]+)', dump, re.M)}

def chunks(data, start, end):
    while start < end:
        kind, header, size = struct.unpack_from('<HHI', data, start)
        if size < header or header < 8 or start + size > end:
            raise ValueError('Invalid resource chunk')
        yield start, kind, header, size
        start += size

def references(xml):
    for off, kind, header, size in chunks(xml, 8, len(xml)):
        if kind == 0x180:
            yield from struct.unpack_from('<' + 'I' * ((size - header) // 4), xml, off + header)
        elif kind == 0x102:
            ext = off + header
            start, stride, count = struct.unpack_from('<HHH', xml, ext + 8)
            for i in range(count):
                value = ext + start + stride * i + 12
                if xml[value + 3] in (1, 2, 7, 8):
                    yield struct.unpack_from('<I', xml, value + 4)[0]

with zipfile.ZipFile(args.apk) as apk:
    table = apk.read('resources.arsc')
    dimensions = {}
    for off, kind, header, size in chunks(table, 12, len(table)):
        if kind != 0x200:
            continue
        package = struct.unpack_from('<I', table, off + 8)[0]
        for pos, tag, head, length in chunks(table, off + header, off + size):
            if tag != 0x201 or table[pos + 8] != 5:
                continue
            config_size = struct.unpack_from('<I', table, pos + 20)[0]
            if any(table[pos + 24:pos + 20 + config_size]):
                continue  # Default configuration; units remain dp/sp, not converted pixels.
            count, entries = struct.unpack_from('<II', table, pos + 12)
            flags = table[pos + 9]
            for i in range(count):
                if flags & 1:
                    index, relative = struct.unpack_from('<HH', table, pos + head + i * 4)
                    relative *= 4
                elif flags & 2:
                    index = i
                    relative = struct.unpack_from('<H', table, pos + head + i * 2)[0]
                    if relative == 0xffff:
                        continue
                    relative *= 4
                else:
                    index = i
                    relative = struct.unpack_from('<I', table, pos + head + i * 4)[0]
                    if relative == 0xffffffff:
                        continue
                entry = pos + entries + relative
                entry_size, entry_flags = struct.unpack_from('<HH', table, entry)
                if entry_flags & 8:  # ResTable_entry FLAG_COMPACT: type packed into flags.
                    value_type = entry_flags >> 8
                    data = struct.unpack_from('<I', table, entry + 4)[0]
                elif not entry_flags & 1:
                    value_type = table[entry + entry_size + 3]
                    data = struct.unpack_from('<I', table, entry + entry_size + 4)[0]
                else:
                    continue
                if value_type == 5:
                    dimensions[(package << 24) | (5 << 16) | index] = data

    layouts = {name for kind, name in names.values() if kind == 'layout'
               and (name.startswith('notification_template_') or name.startswith('notification_material_'))}
    files = {}
    used = set()
    queue = list(layouts)
    while queue:
        name = queue.pop()
        for path in apk.namelist():
            if not path.startswith('res/layout') or path.rsplit('/', 1)[-1] != name + '.xml' or path in files:
                continue
            xml = apk.read(path)
            files[path] = xml
            for resource in references(xml):
                if resource == 0:
                    continue
                if resource not in names:
                    raise ValueError(f'Missing legacy resource name {resource:08x}')
                used.add(resource)
                kind, child = names[resource]
                if kind == 'layout' and child not in layouts:
                    layouts.add(child)
                    queue.append(child)
    lines = []
    for resource in sorted(used):
        kind, name = names[resource]
        literal = f'{dimensions[resource]:08x}' if resource in dimensions else '-'
        lines.append(f'{resource:08x}\t{kind}\t{name}\t{literal}\n')
    files['resources.tsv'] = ''.join(lines).encode()
    files['source.sha256'] = (hashlib.sha256(args.apk.read_bytes()).hexdigest() + '\n').encode()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.output, 'w', compression=zipfile.ZIP_DEFLATED) as out:
        for path, data in sorted(files.items()):
            entry = zipfile.ZipInfo(path, date_time=(2026, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            out.writestr(entry, data)
print(f'Bundled {len(files) - 2} XML files, {len(used)} resource names, '
      f'{sum(r in dimensions for r in used)} original dimensions: {args.output}')
