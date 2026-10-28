import zipfile, shutil, re, os, sys

ROOT = os.path.dirname(os.path.abspath(__file__))
changed = []
for dirpath, _, files in os.walk(ROOT):
    for fn in files:
        if not fn.endswith('.aar'):
            continue
        p = os.path.join(dirpath, fn)
        tmp = p + '.tmp'
        with zipfile.ZipFile(p) as zin, zipfile.ZipFile(tmp, 'w', zipfile.ZIP_DEFLATED) as zout:
            hit = False
            for it in zin.infolist():
                data = zin.read(it.filename)
                if it.filename == 'AndroidManifest.xml':
                    s = data.decode('utf-8')
                    n, k = re.subn(r'minSdkVersion="\d+"', 'minSdkVersion="28"', s)
                    if k:
                        hit = True
                        data = n.encode('utf-8')
                zout.writestr(it, data)
        shutil.move(tmp, p)
        if hit:
            changed.append(fn)
print('repacked', len(changed), 'aars')
for c in sorted(changed): print(' ', c)
