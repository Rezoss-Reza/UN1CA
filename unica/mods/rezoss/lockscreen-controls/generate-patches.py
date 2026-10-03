#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Generate strict patches from clean decodes after the existing patch stacks.
Decoded inputs are read-only. Hook anchors fail closed on firmware drift.
"""
import argparse
import difflib
from pathlib import Path

parser = argparse.ArgumentParser()
for name in ('dressroom', 'aod', 'compiled', 'output'):
    parser.add_argument(name, type=Path)
a = parser.parse_args()
a.output.mkdir(parents=True, exist_ok=True)

class Patch:
    def __init__(self, root):
        self.root, self.changes = root, {}
    def read(self, path):
        return self.changes.get(path, (self.root/path).read_text() if (self.root/path).exists() else '')
    def replace(self, path, old, new):
        text = self.read(path)
        assert text.count(old) == 1, (path, old[:100], text.count(old))
        self.changes[path] = text.replace(old, new)
    def add(self, path, text):
        assert not (self.root/path).exists(), path
        self.changes[path] = text
    def method(self, path, signature, transform):
        text = self.read(path)
        start = text.index(signature)
        end = text.index('.end method', start) + len('.end method')
        changed = transform(text[start:end])
        assert changed != text[start:end], (path, signature, 'no change')
        self.changes[path] = text[:start] + changed + text[end:]
    def write(self, name):
        dest = a.output/name
        assert not dest.exists(), str(dest) + ' exists; back up before regeneration'
        parts = ['AUTHOR Rezoss\nSubject: [PATCH] Add native lockscreen height and widget size controls\n\n']
        for path, new in sorted(self.changes.items()):
            old = (self.root/path).read_text() if (self.root/path).exists() else ''
            parts.extend(difflib.unified_diff(old.splitlines(True), new.splitlines(True),
                         fromfile='a/'+path if old else '/dev/null', tofile='b/'+path, n=3))
        dest.write_text('\n'.join('' if line == ' ' else line for line in ''.join(parts).splitlines()).rstrip() + '\n')

d, o = Patch(a.dressroom), Patch(a.aod)
prefix = 'io/mesalabs/unica/lockscreen/'
for path in sorted(a.compiled.rglob('*.smali')):
    rel = path.relative_to(a.compiled).as_posix()
    if path.stem != 'ResizeLinear':
        d.add('smali_classes3/'+rel, path.read_text())
    if path.stem in ('ResizeLinear', 'ResizeGeometry'):
        o.add('smali_classes3/'+rel, path.read_text())

clock = 'com/samsung/android/lockscreen/editor/item/domain/entity/item/clock/'
widget = 'com/samsung/android/lockscreen/editor/item/domain/entity/item/widget/'
sdk = 'com/samsung/android/sdk/aodservice/entities/item/'
d.replace('smali_classes2/'+clock+'ClockStretchData.smali', '# instance fields', '# instance fields\n.field public unicaHeight:F\n.field public unicaFont:I\n.field public unicaType:I\n')
d.replace('smali_classes2/'+widget+'WidgetData.smali', '# instance fields', '# instance fields\n.field public unicaWidth:F\n.field public unicaHeight:F\n')
d.method('smali_classes2/'+widget+'WidgetData.smali', '.method public final copy(', lambda s: s.replace('    return-object v1', '    move-object/from16 v0, p0\n\n    invoke-static {v0, v1}, L'+prefix+'WidgetControls;->copy(L'+widget+'WidgetData;L'+widget+'WidgetData;)V\n\n    return-object v1'))
# Clone the native slider and labels, including the Samsung expand mode/theme.
layout = d.read('res/layout/clock_panel_layout.xml')
start = layout.index('                <androidx.appcompat.widget.SeslSeekBar')
end = layout.index('                </RelativeLayout>', start) + len('                </RelativeLayout>')
height = layout[start:end]
import re
height = re.sub(r' android:(id|tag)="[^"]*"', '', height)
height = height.replace('@string/description_thickness', 'Clock height')
height = height.replace('@string/clock_panel_clock_font_weight_seekbar_start_title', 'Short')
height = height.replace('@string/clock_panel_clock_font_weight_seekbar_end_title', 'Tall')
height = '<io.mesalabs.unica.lockscreen.HeightSlider android:tag="unica_clock_height" android:layout_width="match_parent" android:layout_height="wrap_content" android:layout_marginTop="8dp">\n' + height + '\n</io.mesalabs.unica.lockscreen.HeightSlider>'
d.replace('res/layout/clock_panel_layout.xml', '                </RelativeLayout>', '                </RelativeLayout>\n' + height)

d.replace('smali_classes2/vo/x.smali', '    invoke-virtual {v2, v7}, Lsp/q;->b(Lvo/i;)V', '    invoke-virtual {v2, v7}, Lsp/q;->b(Lvo/i;)V\n\n    invoke-static {v2, v7}, L'+prefix+'EditorHooks;->clock(Ljava/lang/Object;Ljava/lang/Object;)V')
d.replace('smali_classes2/vo/x.smali', '    check-cast v14, Ljava/lang/Integer;',
          '    check-cast v14, Ljava/lang/Integer;\n\n    invoke-static {v12, v14}, L'+prefix+'HeightSlider;->panelHeight(Landroid/view/View;Ljava/lang/Integer;)Ljava/lang/Integer;\n\n    move-result-object v14')
d.replace('smali_classes2/vo/t.smali', '    :goto_3\n    invoke-virtual {v2}', '    :goto_3\n    invoke-static {v2, v6}, L'+prefix+'HeightSlider;->retain(L'+clock+'ClockData;Ljava/util/List;)Ljava/util/List;\n\n    move-result-object v6\n\n    invoke-virtual {v2}')
# Preserve data-binding root class and existing binding indices.
path = 'res/layout/widget_panel_layout.xml'
s = d.read(path).replace('    <androidx.recyclerview', '    <LinearLayout android:orientation="vertical" android:layout_width="match_parent" android:layout_height="wrap_content">\n    <io.mesalabs.unica.lockscreen.WidgetControls android:tag="unica_widget_size" android:layout_width="match_parent" android:layout_height="wrap_content" />\n    <androidx.recyclerview')
d.changes[path] = s.replace('</FrameLayout>', '    </LinearLayout>\n</FrameLayout>')
d.replace('res/layout/widget_item_layout_v2.xml', '<FrameLayout android:layout_gravity="center"', '<io.mesalabs.unica.lockscreen.ResizeFrame android:layout_gravity="center"')
d.replace('res/layout/widget_item_layout_v2.xml', '</FrameLayout>', '</io.mesalabs.unica.lockscreen.ResizeFrame>')
d.replace('smali_classes2/op/m.smali', '    invoke-virtual {v11, v12}, Lsp/h2;->b(Lpp/y;)V', '    invoke-virtual {v11, v12}, Lsp/h2;->b(Lpp/y;)V\n\n    invoke-static {v11, v0}, L'+prefix+'EditorHooks;->widgets(Ljava/lang/Object;Ljava/lang/Object;)V')
d.replace('smali_classes2/op/m.smali', '    iput-object v3, v0, Lop/m;->H:Landroid/widget/FrameLayout;', '    iput-object v3, v0, Lop/m;->H:Landroid/widget/FrameLayout;\n\n    invoke-static {v3, v0}, L'+prefix+'EditorHooks;->preview(Landroid/view/View;Ljava/lang/Object;)V')
d.replace('smali_classes2/pd/s.smali', '    check-cast p0, L'+widget+'WidgetData;', '    check-cast p0, L'+widget+'WidgetData;\n\n    invoke-static {p0, p1}, L'+prefix+'WidgetControls;->save(L'+widget+'WidgetData;L'+sdk+'LockScreenItem$Builder;)V')
d.replace('smali_classes2/sd/q.smali', '    invoke-virtual {v1, v3}, Lsd/q;->e(Ljava/lang/String;)L'+sdk+'ComplicationWidgets;\n\n    move-result-object v3', '    invoke-virtual {v1, v3}, Lsd/q;->e(Ljava/lang/String;)L'+sdk+'ComplicationWidgets;\n\n    move-result-object v3\n\n    invoke-static {v2, v3}, L'+prefix+'WidgetControls;->load(L'+widget+'WidgetData;L'+sdk+'LockScreenItem;)V')
d.replace('smali_classes2/sd/h.smali', '    const/4 v1, 0x1\n\n    invoke-direct {v0, p0, v1}, Landroid/graphics/Point;-><init>(II)V', '    const/4 v1, 0x2\n\n    invoke-direct {v0, p0, v1}, Landroid/graphics/Point;-><init>(II)V')
d.add('smali_classes3/'+prefix+'EditorHooks.smali', f'''.class public final L{prefix}EditorHooks;
.super Ljava/lang/Object;

.method public static clock(Ljava/lang/Object;Ljava/lang/Object;)V
    .locals 1
    check-cast p0, Landroidx/databinding/ViewDataBinding;
    invoke-virtual {{p0}}, Landroidx/databinding/ViewDataBinding;->getRoot()Landroid/view/View;
    move-result-object p0
    check-cast p1, Lvo/i;
    move-object v0, p1
    iget-object p1, p1, Lvo/i;->e:Lmf/p2;
    iget-object p1, p1, Lmf/p2;->a:Lpe/d;
    invoke-static {{p0, p1, v0}}, L{prefix}HeightSlider;->bind(Landroid/view/View;Ljava/lang/Object;Ljava/lang/Object;)V
    return-void
.end method

.method public static tracking(Ljava/lang/Object;Z)V
    .locals 1
    if-eqz p0, :tracking_done
    check-cast p0, Lvo/i;
    iget-object p0, p0, Lvo/i;->C:Lvo/h;
    const/4 v0, 0x0
    if-eqz p1, :tracking_stop
    invoke-virtual {{p0, v0}}, Lvo/h;->onStartTrackingTouch(Landroidx/appcompat/widget/SeslSeekBar;)V
    return-void
    :tracking_stop
    invoke-virtual {{p0, v0}}, Lvo/h;->onStopTrackingTouch(Landroidx/appcompat/widget/SeslSeekBar;)V
    :tracking_done
    return-void
.end method

.method public static widgets(Ljava/lang/Object;Ljava/lang/Object;)V
    .locals 1
    check-cast p0, Landroidx/databinding/ViewDataBinding;
    invoke-virtual {{p0}}, Landroidx/databinding/ViewDataBinding;->getRoot()Landroid/view/View;
    move-result-object p0
    check-cast p1, Lop/m;
    iget-object v0, p1, Lop/m;->H:Landroid/widget/FrameLayout;
    iget-object p1, p1, Lop/m;->l:Lcg/h;
    iget-object p1, p1, Lcg/h;->a:Lsd/g;
    invoke-static {{p0, p1, v0}}, L{prefix}WidgetControls;->bind(Landroid/view/View;Ljava/lang/Object;Landroid/view/View;)V
    return-void
.end method

.method public static preview(Landroid/view/View;Ljava/lang/Object;)V
    .locals 0
    invoke-virtual {{p0}}, Landroid/view/View;->getParent()Landroid/view/ViewParent;
    move-result-object p0
    check-cast p0, L{prefix}ResizeFrame;
    check-cast p1, Lop/m;
    iget-object p1, p1, Lop/m;->l:Lcg/h;
    iget-object p1, p1, Lcg/h;->a:Lsd/g;
    invoke-virtual {{p1}}, Luc/c;->getData()Ljava/lang/Object;
    move-result-object p1
    check-cast p1, L{widget}WidgetData;
    invoke-virtual {{p0, p1}}, L{prefix}ResizeFrame;->bind(L{widget}WidgetData;)V
    return-void
.end method
''')
comp = 'com/samsung/android/app/facewidget/complication/'
rule_provider = 'smali/'+comp+'ComplicationGridRuleProvider.smali'
for old, new in [('ComplicationFitPositionRule', 'DefaultFitPositionRule'),
                 ('ComplicationDefaultSortPositionRule', 'DefaultSortPositionRule'),
                 ('NormalComplicationDragAndDropRule', 'DefaultDragAndDropRule')]:
    o.method(rule_provider, '.method private final createNormalSizeRules(Z)',
             lambda body, old=old, new=new: body.replace('/'+old+';', '/'+new+';'))

size = 'smali/'+comp+'ComplicationWidgetSizeManager.smali'
o.replace(size, '.field private static final DEFAULT_WIDGET_PHONE_Y:I = 0x1', '.field private static final DEFAULT_WIDGET_PHONE_Y:I = 0x2')
o.method(size, '.method public final getRow()I', lambda s: s.replace('    :cond_3\n    const/4 p0, 0x1', '    :cond_3\n    const/4 p0, 0x2'))
view = 'smali/'+comp+'KeyguardComplicationWidgetContainer.smali'
o.replace(view, '.super Landroid/widget/LinearLayout;', '.super L'+prefix+'ResizeLinear;')
o.replace(view, '    invoke-direct {p0, p1}, Landroid/widget/LinearLayout;-><init>(Landroid/content/Context;)V', '    invoke-direct {p0, p1}, L'+prefix+'ResizeLinear;-><init>(Landroid/content/Context;)V')
assert '.method protected onMeasure(II)V' not in o.read(view) and '.method public onMeasure(II)V' not in o.read(view)
o.changes[view] = o.read(view) + f'''
.method protected onMeasure(II)V
    .locals 1
    iget-object v0, p0, L{comp}KeyguardComplicationWidgetContainer;->complicationWidgetPluginController:L{comp}ComplicationWidgetPluginController;
    invoke-virtual {{v0}}, L{comp}ComplicationWidgetPluginController;->getUnicaWidgetItem()L{sdk}LockScreenItem;
    move-result-object v0
    invoke-virtual {{p0, v0}}, L{prefix}ResizeLinear;->setUnicaItem(L{sdk}LockScreenItem;)V
    invoke-super {{p0, p1, p2}}, L{prefix}ResizeLinear;->onMeasure(II)V
    return-void
.end method
'''
controller = 'smali/'+comp+'ComplicationWidgetPluginController.smali'
o.changes[controller] = o.read(controller) + f'''
.method public final getUnicaWidgetItem()L{sdk}LockScreenItem;
    .locals 1
    iget-object v0, p0, L{comp}ComplicationWidgetPluginController;->aodDataManager:Ljavax/inject/Provider;
    invoke-interface {{v0}}, Ljavax/inject/Provider;->get()Ljava/lang/Object;
    move-result-object v0
    check-cast v0, Lcom/samsung/android/app/aodservice/common/data/AODDataManager;
    iget-object v0, v0, Lcom/samsung/android/app/aodservice/common/data/AODDataManager;->K:L{sdk}ComplicationWidgets;
    return-object v0
.end method
'''


# These accessors consumed p0; one extra local preserves the receiver for scaling.
for signature,axis in [('public final getWidth()I','X'),
                       ('public final getCurrentVerticalHeight()I','Y'),
                       ('public final getHeight(Landroid/content/Context;)I','Y')]:
    def scale_result(s, axis=axis):
        import re
        match=re.search(r'    \.locals (\d+)', s)
        count=int(match.group(1))
        saved='v'+str(count)
        s=s.replace(match.group(0), '    .locals '+str(count+1)+'\n\n    move-object '+saved+', p0', 1)
        s=s.replace('    return p0',
            '    invoke-virtual {'+saved+'}, L'+comp+'ComplicationWidgetPluginController;->getUnicaWidgetItem()L'+sdk+'LockScreenItem;\n'
            '    move-result-object '+saved+'\n'
            '    invoke-static {p0, '+saved+'}, L'+prefix+'ResizeGeometry;->extent'+axis+'(IL'+sdk+'LockScreenItem;)I\n'
            '    move-result p0\n\n    return p0')
        return s
    o.method(controller, '.method '+signature, scale_result)


# Selection and notification avoidance use the same saved frame dimensions.
for signature in ['.method public final getWidgetPositionRect()Landroid/graphics/Rect;',
                  '.method public getOverrideRect()Landroid/graphics/Rect;']:
    def frame_bounds(s):
        s=s.replace('    .locals 0','    .locals 1')
        old='    iget-object p0, p0, L'+widget+'WidgetData;->widgetPositionRect:Landroid/graphics/Rect;'
        assert s.count(old)==1
        return s.replace(old, '    iget-object v0, p0, L'+widget+'WidgetData;->widgetPositionRect:Landroid/graphics/Rect;\n'
            '    invoke-static {p0, v0}, L'+prefix+'WidgetControls;->bounds(L'+widget+'WidgetData;Landroid/graphics/Rect;)Landroid/graphics/Rect;\n'
            '    move-result-object p0')
    d.method('smali_classes2/'+widget+'WidgetData.smali', signature, frame_bounds)

# Include newly added state in native equality checks used for save/change detection.
d.method('smali_classes2/'+widget+'WidgetData.smali', '.method public equals(Ljava/lang/Object;)Z',
    lambda s: s.replace('    check-cast p1, L'+widget+'WidgetData;', '    check-cast p1, L'+widget+'WidgetData;'+"""

    iget v1, p0, L"""+widget+"""WidgetData;->unicaWidth:F
    iget v3, p1, L"""+widget+"""WidgetData;->unicaWidth:F
    cmpl-float v1, v1, v3
    if-nez v1, :unica_size_changed
    iget v1, p0, L"""+widget+"""WidgetData;->unicaHeight:F
    iget v3, p1, L"""+widget+"""WidgetData;->unicaHeight:F
    cmpl-float v1, v1, v3
    if-eqz v1, :unica_size_same
    :unica_size_changed
    return v2
    :unica_size_same
"""))
# Preserve valid incoming editor/profile heights; saved settings are only fallback.
o.method('smali_classes2/io/mesalabs/unica/aod/ClockFontSettingsSync.smali',
    '.method public static normalizeStretchFontData(', lambda s: s.replace('    :try_start_0', """    invoke-virtual {p1}, Lcom/samsung/android/clockpack/plugins/clock/ClockFontData;->hasFontHeights()Z
    move-result v0
    if-nez v0, :cond_return_original

    :try_start_0""", 1))

d.write('0003-Add-clock-height-and-widget-size-controls.patch')
o.write('0008-Expand-and-resize-lockscreen-widget-container.patch')
