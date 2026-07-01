import urllib.request
import zipfile
import lzma
import io

MARKERS = ["BOOTX64.EFI", "fallback.efi", "MokManager.efi", "grubx64_real.efi"]

VERSIONS = [
    ("v1.1.12", "https://github.com/ventoy/Ventoy/releases/download/v1.1.12/ventoy-1.1.12-windows.zip"),
    ("v1.1.14", "https://github.com/ventoy/Ventoy/releases/download/v1.1.14/ventoy-1.1.14-windows.zip"),
    ("v1.1.15", "https://github.com/ventoy/Ventoy/releases/download/v1.1.15/ventoy-1.1.15-windows.zip"),
]


def check_markers(tag, url):
    print("Downloading %s ..." % tag)
    req = urllib.request.Request(url, headers={"User-Agent": "VentoidUpdateChecker/1.0"})
    with urllib.request.urlopen(req) as resp:
        raw = resp.read()
    print("  Downloaded %d MB" % (len(raw) // 1024 // 1024))
    with zipfile.ZipFile(io.BytesIO(raw)) as z:
        xz_name = next(n for n in z.namelist() if n.endswith("ventoy/ventoy.disk.img.xz"))
        data = lzma.decompress(z.read(xz_name))
    results = {}
    for m in MARKERS:
        results[m] = (m.encode("ascii") in data) or (m.encode("utf-16-le") in data)
    return results


for tag, url in VERSIONS:
    r = check_markers(tag, url)
    found = [m for m in MARKERS if r[m]]
    missing = [m for m in MARKERS if not r[m]]
    print("  %s: FOUND=%s  MISSING=%s" % (tag, found, missing))
    print()
