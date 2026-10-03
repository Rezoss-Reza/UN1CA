#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Resolve helper calls and fields against the exact patched Samsung smali APIs."""
from pathlib import Path
import re
import sys

for argument in sys.argv[1:]:
    root=Path(argument)
    classes={}
    helpers=[]
    for path in root.glob('smali*/**/*.smali'):
        text=path.read_text()
        match=re.search(r'^\.class[^\n]*? (L[^;]+;)$',text,re.M)
        if not match: continue
        methods=set(re.findall(r'^\.method .*?([^\s]+\([^\n]+)$',text,re.M))
        fields=set(re.findall(r'^\.field .*?([^\s=:]+:[^\s=]+)',text,re.M))
        supers=re.findall(r'^\.(?:super|implements) (L[^;]+;)',text,re.M)
        classes[match.group(1)]=(methods,fields,supers)
        if '/io/mesalabs/unica/lockscreen/' in str(path): helpers.append((path,text))
    def resolves(owner,symbol,field=False,seen=None):
        seen=set() if seen is None else seen
        if owner in seen: return False
        seen.add(owner)
        info=classes.get(owner)
        if info is None: return owner.startswith(('Landroid/', 'Ljava/', 'Ljavax/'))
        if symbol in info[int(field)]: return True
        return any(resolves(parent,symbol,field,seen) for parent in info[2])
    errors=[];checked=0
    for path,text in helpers:
        for owner,symbol in re.findall(r'(L[^\s;,{}]+;)->([^\s,]+)',text):
            if owner.startswith(('Landroid/','Ljava/','Ljavax/')): continue
            if resolves(owner,symbol,'(' not in symbol): checked+=1
            else: errors.append(f'{path.name}: {owner}->{symbol}')
    if errors:
        print('\n'.join(errors));raise SystemExit('Unresolved helper API references')
    print(f'{root.name}: {checked} helper API references resolved')
