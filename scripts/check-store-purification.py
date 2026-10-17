#!/usr/bin/env python3
"""Static contract checks against apktool smali for Market 26.9.4_CN; no app build."""
import argparse
import hashlib
from pathlib import Path
import re
import xml.etree.ElementTree as ET

p = argparse.ArgumentParser()
p.add_argument('--reference', type=Path, default=Path('/private/tmp/coloros-store-cn/src'))
p.add_argument('--apk', type=Path, default=Path('/private/tmp/coloros-cn-reference-20261017/_26.9.4_CN.apk'))
a = p.parse_args()
repo = Path(__file__).resolve().parents[1]
checks = 0

def check(ok, message):
    global checks
    if not ok:
        raise AssertionError(message)
    checks += 1


def smali(name):
    files = list(a.reference.glob('smali*/' + name.replace('.', '/') + '.smali'))
    check(len(files) == 1, 'Missing/ambiguous target ' + name)
    return files[0].read_text()


def method(name, signature):
    text = smali(name)
    check(any(line.startswith('.method ') and line.endswith(' ' + signature)
              and ' abstract ' not in line for line in text.splitlines()), name + '#' + signature)
    return text

check(hashlib.sha256(a.apk.read_bytes()).hexdigest() ==
      '391a2fb1e9bd5d22331656b44d42837ef9048ab754b1a72996f24daeb0a49179', 'Wrong APK digest')
meta = (a.reference / 'apktool.yml').read_text()
check('versionCode: 260904' in meta and 'versionName: 26.9.4_CN' in meta, 'Wrong version')
manifest = ET.parse(a.reference / 'AndroidManifest.xml').getroot()
check(manifest.get('package') == 'com.heytap.market', 'Wrong target package')
page = 'com.heytap.cdo.client.cards.page.'
market = 'com.heytap.market.'
detail = 'com.heytap.cdo.client.detail.cn.app.tabcontent.'
dto = 'com.heytap.cdo.card.domain.dto.'
method('a.a.a.w3f', 'isSupportSplash()Z')
method('a.a.a.w3f', 'hasSplash()Z')
method(page + 'struct.TabDataPresenter', 'ނ()Lcom/heytap/card/main/api/model/StructDto;')
method('a.a.a.n3b', 'ֈ(Ljava/util/List;)V')
method(page + 'base.CardFragment', 'ຑ()Lcom/heytap/card/api/listener/CardApiRecycleViewAdapter;')
method(page + 'main.home.HomeChosenCardAdapterPresenter', '<init>(Lcom/heytap/card/api/listener/CardApiRecycleViewAdapter;Lcom/heytap/cdo/client/cards/page/base/stat/IStatPresenter;Lcom/nearme/uikit/widget/recycler/CdoRecyclerView;)V')
method(market + 'search.core.fragment.home.SearchHomeCardAdapterPresenter', '<init>(Lcom/heytap/card/api/listener/CardApiRecycleViewAdapter;Lcom/heytap/cdo/client/cards/page/base/stat/IStatPresenter;)V')
for adapter in ['com.nearme.cards.adapter.RecyclerViewCardAdapter', 'com.heytap.card.api.listener.CardApiRecyclerViewAdapterProxy']:
    for name in ['setDatas', 'addDataAndNotifyChanged']:
        method(adapter, name + '(Ljava/util/List;)V')
method(market + 'search.core.recommend.a', 'getNextRecommendInfo(I)La/a/a/q9e;')
method(market + 'search.core.recommend.a', 'getRecommendWordSize()I')
method(market + 'mine.MineFragment', 'ࢯ(Lcom/heytap/cdo/card/domain/dto/ViewLayerWrapDto;Z)V')
method(detail + 'detail.a', 'ԭ(Lcom/heytap/cdo/client/detail/cn/app/tabcontent/detail/b;)V')
method(detail + 'recommend.TabRecommendContentView', 'ޞ(La/a/a/sn2;)V')
base = market + 'appmanage.core.upgrade.upgrademgr.BaseRecyclerViewDownloadFragment'
for suffix in ['$e', '$f']:
    text = method(base + suffix, 'Ԫ(La/a/a/ik3;)V')
    check('L' + base.replace('.', '/') + ';' in text, 'Missing callback owner')
for name in ['ViewLayerWrapDto', 'NavCardDto', 'BannerCardDto', 'ToolsCardDto']:
    method(dto + name, '<init>()V')
    check(not re.search(r'^\.field .* final (?!.* static ).*:', smali(dto + name), re.M), 'Final DTO instance state ' + name)
method('com.heytap.card.main.api.model.StructDto', '<init>()V')
for name, signatures in {
    'ViewLayerWrapDto': ['getCards()Ljava/util/List;', 'setCards(Ljava/util/List;)V'],
    'NavCardDto': ['getBanners()Ljava/util/List;', 'setBanners(Ljava/util/List;)V'],
    'BannerCardDto': ['getBanners()Ljava/util/List;', 'setBanners(Ljava/util/List;)V'],
    'ToolsCardDto': ['getToolItems()Ljava/util/List;', 'setToolItems(Ljava/util/List;)V'],
    'ToolItemDto': ['getType()Ljava/lang/String;'],
    'BannerDto': ['getActionParam()Ljava/lang/String;'],
    'CardDto': ['getCode()I'],
}.items():
    for signature in signatures:
        method(dto + name, signature)
for signature in ['getTabDtoList()Ljava/util/List;', 'setTabDtoList(Ljava/util/List;)V', 'getDefaultSelected()I', 'setDefaultSelected(I)V']:
    method('com.heytap.card.main.api.model.StructDto', signature)
method('com.heytap.card.main.api.model.TabDto', 'getId()I')
for name in ['AppCardDto', 'AppListCardDto', 'ResourceListCardDto', 'DailyRecommendListCardDto']:
    smali(dto + name)

# Style paths: validate original names, field roles and native fallbacks.
resource = 'com.heytap.cdo.common.domain.dto.ResourceDto'
for signature in ['getGifIconUrl()Ljava/lang/String;', 'getIconStyle()I', 'getHighlight()I',
                  'getSpecial()I', 'getBg()Ljava/lang/String;',
                  'getSuperThemeRights()Lcom/heytap/cdo/common/domain/dto/supertheme/SuperThemeRightsDto;']:
    method(resource, signature)
method('com.heytap.cdo.detail.domain.dto.detail.BaseDetailDto', 'getGifIconUrl()Ljava/lang/String;')
method('com.heytap.cdo.common.domain.dto.supertheme.SuperThemeRightsDto', 'getIconPendantDynamic()Ljava/lang/String;')

# Software is native main-tab 20, never the unrelated home presenter or search fragment.
normal = page + 'main.normal.NormalGroupFragment'
method(normal, 'onViewCreated(Landroid/view/View;Landroid/os/Bundle;)V')
method(normal, 'getHeight()I')
method('com.nearme.module.ui.fragment.BaseFragment', 'onResume()V')
method('a.a.a.k7a', 'ԩ(Lcom/heytap/card/main/api/model/TabDto;)Landroidx/fragment/app/Fragment;')
check('.super Lcom/heytap/cdo/client/cards/page/main/normal/NormalGroupFragment;' in smali(page + 'main.app.AppGroupFragment'), 'Software superclass')
for target, edges in {
    normal: ['ࢰ:Landroid/os/Bundle;', 'La/a/a/y57;->ԩ(Landroid/os/Bundle;)',
             'La/a/a/y57;->Ԭ', '->setAdapter(', '->isEmpty()Z', '->getArguments()Landroid/os/Bundle;'],
    'a.a.a.k7a': ['0x14', '->ԩ(Lcom/heytap/card/main/api/model/TabDto;)',
                 'Lcom/heytap/cdo/client/cards/page/main/app/AppGroupFragment;'],
    'a.a.a.q6a': ['module_tab_soft', 'generateAppTab', '0x14'],
    'a.a.a.y57': ['key.group.fragment.item', '->getParcelable(Ljava/lang/String;)'],
    'a.a.a.z07': ['loadAndShowImage', 'Landroid/text/TextUtils;->isEmpty'],
    'a.a.a.unf': ['->getIconPendantDynamic()', '->getIconPendant()'],
    'a.a.a.tyf': ['->getSpecial()I', '->getBg()', '->NROMAL_THEME:', '->SKIN_THEME:', '->VIDEO_THEME:'],
}.items():
    text = smali(target)
    for edge in edges:
        check(edge in text, 'Native call evidence: ' + target + ' ' + edge)
layout = ET.parse(a.reference / 'res/layout/card_page_fragment_group_maintab_normal.xml').getroot()
check(layout.tag == 'FrameLayout', 'Software root must support removing all children')
src = (repo / 'app/src/main/java/com/rikumi/colorosmod/hooks/StorePurificationHooks.java').read_text()
main = (repo / 'app/src/main/java/com/rikumi/colorosmod/MainActivity.kt').read_text()
keys = {'store_' + key for key in re.findall(r'enabled\("([a-z_]+)"\)', src)}
check(len(keys) == 16, 'Expected 16 independent current options')
for old in ['store_home_category_only', 'store_blank_search_tab', 'StoreSearchHomeHooks', 'setCurrentItemInternal', 'HomeSubTabPresenter']:
    check(old not in src and old not in main, 'Retired implementation remains: ' + old)
check(not (repo / 'app/src/main/java/com/rikumi/colorosmod/hooks/StoreSearchHomeHooks.java').exists(), 'Remove search-only class')
check('readBool("store_" + option, false)' in src, 'Default off')
check('enabled("empty_software")' in src and '"empty_software",' not in src, 'Independent new preference')
check('SOFTWARE.equals(p.thisObject.getClass().getName())' in src, 'Exact Software scope')
check('"ࢰ", new Bundle()' in src and 'parent.removeView(content)' in src, 'Remove page data and only content views')
check('"getFragments"' in src and '"remove", fragment' in src and '"commitNowAllowingStateLoss"' in src,
      'Retire restored child pages instead of leaving hidden pager contents')
software = src[src.index('private static void installEmptySoftware'):src.index('private static void tagPresenter')]
check('setArguments' not in software and 'setTabDtoList' not in software, 'Do not mutate original arguments/nav models')
check('previous != enabled("empty_software")' in software and '.recreate()' in software,
      'Restore both toggle directions through native lifecycle')
check('Class.forName("a.a.a.bg", false, cl), "Ԭ", bar' in software, 'Retain native header height')
check('AppChosenCardFragment' not in src, 'Legacy home option must no longer affect Software')
check('StoreSearch' not in src and 'getItemId' not in src and 'withSearch' not in src,
      'No injected tab, stable ID or forced default remains')
check('Math.max(0, kept.indexOf(selectedTab))' in src, 'Native selected model preserved with unrelated hide filters')
check('info.getLongVersionCode() != 260904L' in src and '!"26.9.4_CN".equals(info.versionName)' in src, 'Version boundary')
for locale in ['values', 'values-en', 'values-zh-rHK', 'values-zh-rTW']:
    entries = ET.parse(repo / ('app/src/main/res/' + locale + '/store_purification.xml')).getroot()
    names = {e.get('name') for e in entries}
    check(keys <= names, locale + ' missing current option translations')
    check('store_home_category_only' not in names and 'store_blank_search_tab' not in names, locale + ' obsolete strings')
    check(len(names) == len(entries), locale + ' duplicate keys')
    for key in keys:
        check('SwitchItem("' + key + '", R.string.' + key in main, 'Setting entry: ' + key)
check(ET.parse(repo / 'app/src/main/res/values/store_purification.xml').getroot().find("string[@name='store_empty_software']").text == '去除软件页所有内容', 'Exact requested title')
check('Boolean.TRUE.equals(previous)' in software and 'args == null ? new Bundle() : args' in software,
      'View-only recreation must restore native bundle after disabling')
# Preserve the complete native header; remove sibling tabs/pager, not the root.
check('removeAllViews' not in software and 'new String[]{"ࢫ", "ࢮ"}' in software,
      'Only strip and pager may leave the hierarchy')
check('setPadding' not in software and 'setLayoutParams' not in software,
      'Keep native header dimensions and responsive margins')
method('a.a.a.bg', 'Ԭ(Lcom/heytap/cdo/client/cards/page/main/common/actionbar/view/MainActionBar;)I')
for field in ['ࢩ:Lcom/heytap/cdo/client/cards/page/main/common/actionbar/view/MainActionBar;',
              'ࢫ:Lcom/coui/appcompat/tablayout/COUITabLayout;',
              'ࢮ:Lcom/nearme/module/ui/view/CdoViewPager;']:
    check(field in smali(normal), 'Native retained/removed field ' + field)
for target, edges in {
    page + 'main.common.actionbar.presenter.MainActionBarPresenter':
        ['La/a/a/byf;', 'La/a/a/o4a;', '->getSearchLayout()', '->getMdSwitchView()'],
    'a.a.a.byf': ['->setOnClickListener(', 'La/a/a/z8e;->ԩ'],
    'a.a.a.o4a': ['->setOnClickListener(', 'oap://mk/md'],
    'a.a.a.z8e': ['oap://mk/search'],
    page + 'main.common.actionbar.view.PercentTabWithSearchView':
        ['->getChildCount()I', 'Lcom/coui/appcompat/tablayout/COUITabLayout;', '->setMeasuredDimension(II)V'],
}.items():
    text = smali(target)
    for edge in edges:
        check(edge in text, 'Retained header path ' + target + ' ' + edge)
ns = '{http://schemas.android.com/apk/res/android}'
container = next(e for e in layout.iter() if e.get(ns + 'id') == '@id/container')
check([e.get(ns + 'id') for e in container] == ['@id/actionbar_maintab', '@id/tab_layout'],
      'Search header and strip must be siblings')
bar_layout = ET.parse(a.reference / 'res/layout/card_page_main_tab_actionbar.xml').getroot()
check(any(e.get(ns + 'id') == '@id/view_md_switcher' for e in bar_layout), 'Download control belongs to retained header')
print(f'PASS: {checks} APK/smali/settings/static contract checks; no build or device validation')
