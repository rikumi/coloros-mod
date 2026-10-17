#!/usr/bin/env python3
"""不构建 APK；用 JVM 桩验证生产 hook 的门控、范围与 Intent 保留。"""
from pathlib import Path
import subprocess
import tempfile
import textwrap
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
JAVA = Path('/opt/homebrew/opt/openjdk@21/bin')
SOURCE = ROOT / 'app/src/main/java/com/rikumi/colorosmod/hooks/AppStartConfirmHooks.java'
KEY = 'app_start_confirm_always_allow_enabled'

STUBS = {
    'android/content/Intent.java': '''
        package android.content;
        import java.util.*;
        public class Intent {
            public String action;
            public final Map<String,Object> extras = new HashMap<>();
            public Intent(String a) { action=a; }
            public Intent(Intent i) { action=i.action; extras.putAll(i.extras); }
            public String getAction() { return action; }
            public int getIntExtra(String k,int d) {
                Object v=extras.get(k); return v instanceof Integer ? (Integer)v : d;
            }
            public Intent putExtra(String k,int v) { extras.put(k,v); return this; }
        }
    ''',
    'android/app/Activity.java': '''
        package android.app;
        import android.content.Intent;
        public class Activity {
            public Intent intent; public int setCalls;
            public Intent getIntent() { return intent; }
            public void setIntent(Intent i) { intent=i; setCalls++; }
        }
    ''',
    'android/os/Bundle.java': 'package android.os; public class Bundle {}',
    'com/rikumi/colorosmod/XposedInit.java': '''
        package com.rikumi.colorosmod;
        public class XposedInit {
            public static final String KEY_APP_START_CONFIRM_ALWAYS_ALLOW_ENABLED =
                "app_start_confirm_always_allow_enabled";
            public static boolean enabled;
            public static boolean readBool(String key,boolean fallback) { return enabled; }
            public static void log(String message) {}
        }
    ''',
    'com/rikumi/colorosmod/xposed/XC_LoadPackage.java': '''
        package com.rikumi.colorosmod.xposed;
        public class XC_LoadPackage {
            public static class LoadPackageParam { public ClassLoader classLoader; }
        }
    ''',
    'com/rikumi/colorosmod/xposed/XC_MethodHook.java': '''
        package com.rikumi.colorosmod.xposed;
        public abstract class XC_MethodHook {
            protected void beforeHookedMethod(MethodHookParam param) {}
            public void fire(Object activity) {
                MethodHookParam p=new MethodHookParam(); p.thisObject=activity;
                beforeHookedMethod(p);
            }
            public static class MethodHookParam { public Object thisObject; }
        }
    ''',
    'com/rikumi/colorosmod/xposed/XposedHelpers.java': '''
        package com.rikumi.colorosmod.xposed;
        public class XposedHelpers {
            public static XC_MethodHook installed;
            public static void findAndHookMethod(String name,ClassLoader loader,
                    String method,Object... args) {
                if (!name.equals("com.oplusos.securitypermission.permission.ui.AppStartConfirmDialogActivity")
                        || !method.equals("onCreate") || args[0]!=android.os.Bundle.class)
                    throw new AssertionError("unexpected hook target");
                installed=(XC_MethodHook)args[args.length-1];
            }
        }
    ''',
    'com/rikumi/colorosmod/hooks/AppStartConfirmTest.java': '''
        package com.rikumi.colorosmod.hooks;
        import android.app.Activity;
        import android.content.Intent;
        import com.rikumi.colorosmod.XposedInit;
        import com.rikumi.colorosmod.xposed.*;
        public class AppStartConfirmTest {
            static int checks;
            static final String ACTION="oplus.app.action.CHECK_ALLOW_START_ACTIVITY";
            static final String VERSION="activity_start_confirm_version";
            static void check(boolean value) {
                if (!value) throw new AssertionError("check "+(checks+1)); checks++;
            }
            static Intent input(int type,int version) {
                Intent i=new Intent(ACTION).putExtra("start_type",type).putExtra(VERSION,version);
                i.extras.put("caller_package","caller"); i.extras.put("callee_package","callee");
                i.extras.put("caller_uid",200001); i.extras.put("callee_uid",200002);
                i.extras.put("extra_userid",2); i.extras.put("request_code",42);
                i.extras.put("android.intent.extra.INTENT",new Object());
                return i;
            }
            public static void main(String[] args) {
                check(AppStartConfirmHooks.dialogIntent(null,true)==null);
                for (int version=-1;version<=3;version++) {
                    for (int type=-1;type<=10;type++) {
                        Intent original=input(type,version);
                        check(AppStartConfirmHooks.dialogIntent(original,false)==original);
                        Intent out=AppStartConfirmHooks.dialogIntent(original,true);
                        boolean eligible=version==2&&(type==0||type==5||type==6);
                        check((out!=original)==eligible);
                        check(original.getIntExtra(VERSION,-99)==version);
                        check(out.getIntExtra(VERSION,-99)==(eligible?0:version));
                        for (String k:original.extras.keySet()) {
                            if (!k.equals(VERSION)) check(out.extras.get(k)==original.extras.get(k));
                        }
                        check(out.getAction().equals(ACTION));
                    }
                }
                Intent wrong=input(0,2); wrong.action="other.action";
                check(AppStartConfirmHooks.dialogIntent(wrong,true)==wrong);
                Intent missing=input(0,2); missing.extras.remove("start_type");
                check(AppStartConfirmHooks.dialogIntent(missing,true)==missing);
                Intent unknown=input(0,2); unknown.extras.remove(VERSION);
                check(AppStartConfirmHooks.dialogIntent(unknown,true)==unknown);
                AppStartConfirmHooks.hook(new XC_LoadPackage.LoadPackageParam());
                check(XposedHelpers.installed!=null);
                Activity a=new Activity(); a.intent=input(0,2);
                XposedHelpers.installed.fire(a);
                check(a.setCalls==0); // 默认关闭，连 setIntent 都不执行。
                XposedInit.enabled=true;
                XposedHelpers.installed.fire(a);
                check(a.setCalls==1&&a.intent.getIntExtra(VERSION,-1)==0);
                XposedInit.enabled=false;
                Activity next=new Activity(); next.intent=input(0,2);
                XposedHelpers.installed.fire(next);
                check(next.setCalls==0&&next.intent.getIntExtra(VERSION,-1)==2);
                System.out.println("PASS: "+checks+" JVM checks (mock Android; no device validation)");
            }
        }
    ''',
}

with tempfile.TemporaryDirectory(prefix='coloros-app-start-', dir='/private/tmp') as temp:
    base = Path(temp)
    files = []
    for name, content in STUBS.items():
        path = base / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(textwrap.dedent(content))
        files.append(str(path))
    subprocess.run([str(JAVA / 'javac'), '-d', str(base / 'classes'), str(SOURCE), *files], check=True)
    subprocess.run([str(JAVA / 'java'), '-cp', str(base / 'classes'),
                    'com.rikumi.colorosmod.hooks.AppStartConfirmTest'], check=True)

for locale in ('values', 'values-en', 'values-zh-rHK', 'values-zh-rTW'):
    path = ROOT / 'app/src/main/res' / locale / 'app_start_confirm_strings.xml'
    strings = {s.attrib['name']: s.text for s in ET.parse(path).getroot()}
    assert set(strings) == {'app_start_confirm_permission_group', 'app_start_confirm_always_allow'}
    assert all(strings.values())
entry = (ROOT / 'app/src/main/java/com/rikumi/colorosmod/XposedInit.java').read_text()
main = (ROOT / 'app/src/main/java/com/rikumi/colorosmod/MainActivity.kt').read_text()
scope = (ROOT / 'app/src/main/resources/META-INF/xposed/scope.list').read_text().splitlines()
assert KEY in entry and KEY in main
assert main.count('SwitchItem("' + KEY + '"') == 1
purification = main.split('private val PURIFICATION:', 1)[1].split('\n)\n', 1)[0]
assert ('GroupTitleItem(R.string.app_start_confirm_permission_group),\n'
        '    SwitchItem("' + KEY + '", R.string.app_start_confirm_always_allow)') in purification
assert 'Category("purification", R.string.weather_category_purification, MiuixIcons.Tune, PURIFICATION)' in main
assert 'APP_BEHAVIOR' not in main and 'app_behavior_category' not in main
assert entry.count('"com.oplus.securitypermission".equals(') == 2
assert 'AppStartConfirmHooks.hook(lpparam);' in entry
assert 'com.oplus.securitypermission' in scope
print('PASS: setting key, package routing, static scope and four locale resources')
