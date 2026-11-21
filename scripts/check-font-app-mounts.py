from pathlib import Path
import os,subprocess,tempfile
source=(Path(__file__).resolve().parents[1] / 'app/src/main/assets/system-font-mounts.sh').read_text()
selection=source[source.index('selected_pids()'):source.index('namespace_id()')]
with tempfile.TemporaryDirectory() as t:
 p=Path(t);(p/'packages').write_text('com.android.chrome\ncom.google.android.webview\nai.lody.android\n');(p/'webview-provider').write_text('com.google.android.webview')
 ps='''PID ARGS
1 init
2 zygote64
3 com.android.chrome
4 com.android.chrome:privileged_process0
5 com.android.chrome_zygote
6 com.android.chromecast.app
7 webview_zygote
8 com.google.android.webview:sandboxed_process0:service:0
9 ai.lody.android
10 ai.lody.android:remote
11 ai.lody.android.other
12 some.other.app
'''
 code='BASE='+str(p)+'\nps() { cat <<\'EOF\'\n'+ps+'EOF\n}\n'+selection+'\nselected_pids\n'
 r=subprocess.run(['sh','-eu'],input=code,text=True,capture_output=True,check=True)
 assert r.stdout.split()==['3','4','5','7','8','9','10'],r
 (p/'packages').write_text('ai.lody.android\n')
 r=subprocess.run(['sh','-eu'],input=code,text=True,capture_output=True,check=True)
 assert r.stdout.split()==['9','10'],r
 (p/'packages').write_text('')
 r=subprocess.run(['sh','-eu'],input=code,text=True,capture_output=True,check=True)
 assert not r.stdout,r
print('PASS: selected host/subprocess/Chrome Zygote/provider renderer selection; package prefix isolation; removable defaults; empty list')
transaction=source[source.index(': > "$BASE/transaction"'):source.index('printf \'%s\\n\' "$version" > "$BASE/current.tmp"')]
# Replace only the process inventory; all transaction selection, deduplication and rollback are real.
transaction=transaction.replace('for pid in $selected $(cleanup_pids); do', 'for pid in $selected 1 10 11 12 20 21; do')
old='a'*64;new='b'*64
for mode,fail in [('apply',False),('restore',False),('apply',True)]:
 with tempfile.TemporaryDirectory() as t:
  p=Path(t);proc=p/'proc';proc.mkdir()
  for pid,tag in [(1,old),(10,''),(11,old),(12,''),(20,old),(21,'')]:
   directory=proc/str(pid);(directory/'ns').mkdir(parents=True);(directory/'ns/mnt').touch();(directory/'root/system/etc').mkdir(parents=True)
   (directory/'root/system/etc/fonts.xml').write_text(f'<!-- coloros-mod-fonts:{tag} -->' if tag else '<fonts/>')
  prefix=f'''BASE={p}
cleanup_all=1
trace() {{ :; }}
TAB=$(printf '\\t')
mode={mode}
version={new if mode=='apply' else ''}
selected_pids() {{ echo '10 11'; }}
namespace_candidate() {{ return 0; }}
namespace_id() {{ if [ "$1" = 12 ]; then echo 10; else echo "$1"; fi; }}
namespace_matches() {{ return 0; }}
valid_version() {{ [ ${{#1}} = 64 ]; }}
fail() {{ echo "$*" >&2; exit 1; }}
in_namespace() {{ echo "$1:$4" >> "$BASE/operations"; {'[ "$1" != 11 ] || [ "$4" != '+new+' ]' if fail else 'return 0'}; }}
'''
  r=subprocess.run(['sh','-eu'],input=prefix+transaction.replace('/proc/',str(proc)+'/'),text=True,capture_output=True)
  operations=(p/'operations').read_text().splitlines()
  if mode=='restore':
   assert r.returncode==0 and operations==['11:','1:','20:'],(operations,r.stderr)
  elif fail:
   assert r.returncode!=0 and operations[:2]==['10:'+new,'11:'+new],(operations,r.stderr)
   assert operations[2:]==['10:','11:'+old,'1:'+old,'20:'+old],operations
  else:
   assert r.returncode==0 and operations==['10:'+new,'11:'+new,'1:','20:'],(operations,r.stderr)
print('PASS: exact transaction mounts selected namespaces only, cleans legacy init/unselected mounts, skips unrelated stock namespaces, restores owned mounts and rolls back original per-namespace versions')

watch=source[source.index('if [ "${1:-}" = watch ]; then'):source.index('mkdir -p "$BASE"',source.index('if [ "${1:-}" = watch ]; then'))]
for present,matching in [(True,True),(True,False),(False,False)]:
 with tempfile.TemporaryDirectory() as t:
  p=Path(t);(p/'requested').write_text(new)
  xml=p/'proc/42/root/system/etc/fonts.xml';xml.parent.mkdir(parents=True)
  xml.write_text('<!-- coloros-mod-fonts:'+ (new if matching else old) +' -->')
  backend=p/'backend.sh';backend.write_text('echo resume >> "$BASE/watch-calls"\n')
  setup=f"""BASE={p}
export BASE
SELF={backend}
flock() {{ return 0; }}
selected_pids() {{ {'echo 42' if present else 'true'}; }}
sleep() {{ rm -f "$BASE/requested"; }}
"""
  r=subprocess.run(['sh','-eu','-s','--','watch'],input=setup+watch.replace('/proc/',str(p/'proc')+'/').replace('/system/bin/sh','/bin/sh'),text=True,capture_output=True,timeout=5)
  assert r.returncode==0,r.stderr
  assert (p/'watch-calls').exists() == (present and not matching)
print('PASS: watcher leaves matching/absent targets idle, resumes for a newly unmounted target, and exits when disabled')

policy=source[source.index('cleanup_all=0'):source.index('cleanup_pids()')]
for scoped,mode,before,after,expected in [
 (False,'apply',['a.b'],['a.b'],1),
 (True,'apply',['a.b'],['a.b'],0),
 (True,'resume',['a.b'],['a.b'],0),
 (True,'apply',['a.b'],['a.b','c.d'],0),
 (True,'apply',['a.b','c.d'],['a.b'],1),
 (True,'restore',['a.b'],['a.b'],1),
 (True,'apply',[],[],0),
]:
 with tempfile.TemporaryDirectory() as t:
  p=Path(t)
  if scoped: (p/'scoped-policy').touch()
  (p/'packages').write_text('\n'.join(after)+'\n')
  (p/'applied-packages').write_text('\n'.join(before)+'\n')
  r=subprocess.run(['sh','-eu'],input=f'BASE={p}\nmode={mode}\n'+policy+'\necho "$cleanup_all"\n',text=True,capture_output=True,check=True)
  assert r.stdout.strip()==str(expected),(scoped,mode,before,after,r)
print('PASS: global cleanup runs only for initial migration, removals and restore; routine apply/resume/additions stay scoped')

ownership=source[source.index('init_path()'):source.index('in_namespace()')]
with tempfile.TemporaryDirectory() as t:
 p=Path(t);base=p/'adb';configs=p/'fonts/configs';target=p/'fonts.xml'
 legacy=base/'v2'/old;published=configs/old
 for directory in [legacy,published]:
  (directory/'configs').mkdir(parents=True)
  (directory/'configs/cfg-0.xml').write_text('<fonts/>')
 (legacy/'ready').touch()
 os.link(str(legacy/'configs/cfg-0.xml'),str(target))
 setup=f'BASE={base}\nCONFIGS={configs}\n'+ownership
 for migrated in [False,True]:
  if migrated: (published/'ready').touch()
  r=subprocess.run(['sh','-eu'],input=setup+f'owns_file {old} cfg-0.xml {target}\ninit_path {old}\n',text=True,capture_output=True,check=True)
  assert r.stdout.strip()==str(published if migrated else legacy),r
 target.unlink();target.write_text('<unrelated/>')
 r=subprocess.run(['sh','-eu'],input=setup+f'owns_file {old} cfg-0.xml {target}\n',text=True,capture_output=True)
 assert r.returncode!=0,r
print('PASS: published snapshots use /data/fonts; legacy bind ownership remains recognizable; unrelated files are rejected')

prepare=source[source.index('    if [ "$action" = prepare ]; then'):source.index('    old=$(current_version)')]
with tempfile.TemporaryDirectory() as t:
 p=Path(t);base=p/'adb';cache=p/'fonts';stock=p/'system/etc/fonts.xml';stock.parent.mkdir(parents=True);stock.write_text('<fonts/>')
 snapshot=base/'snapshots'/new;snapshot.mkdir(parents=True)
 xml=f'<!-- coloros-mod-fonts:{new} --><fonts/>'
 (snapshot/'cfg-0.xml').write_text(xml)
 (snapshot/'configs.list').write_text(f'{stock}\tcfg-0.xml\n')
 setup=f'BASE={base}\nCACHE={cache}\nCONFIGS=$CACHE/configs\nversion={new}\naction=prepare\nTAB=$(printf "\\t")\nvalid_version() {{ return 0; }}\nconfig_target() {{ return 0; }}\nchcon() {{ :; }}\nfail() {{ exit 1; }}\n'
 code=prepare.replace("grep -q '^/system/etc/fonts.xml'", "grep -q '^"+str(stock)+"'")
 r=subprocess.run(['sh','-eu'],input=setup+code,text=True,capture_output=True,check=True)
 published=cache/'configs'/new
 assert (published/'configs/cfg-0.xml').read_text()==xml
 assert (published/'ready').is_file() and not (base/'v2').exists()
 assert (published/'configs/cfg-0.xml').stat().st_mode & 0o777 == 0o444
print('PASS: preparation publishes immutable XML outside /data/adb without creating a legacy bind source')
