"""Read apt-acquired DEP-11 catalog as inert data, never command-line templates."""
import glob
import json
import os
import struct
import tarfile
import tempfile
import re
from pathlib import Path
import yaml

PKG = re.compile(r"[a-z0-9][a-z0-9+.-]{1,127}\Z")
DESKTOP = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,159}\.desktop\Z")

ICON = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.+-]{0,159}\.png\Z")
ICON_LIMIT = 256 * 1024

def cached_icon(record):
    icons = record.get("Icon")
    values = icons.get("cached") if isinstance(icons, dict) else []
    if not isinstance(values, list): return None
    return next((i["name"] for i in values if isinstance(i, dict)
                 and i.get("width") == 64 and i.get("height") == 64
                 and isinstance(i.get("name"), str) and ICON.fullmatch(i["name"])), None)

def sync_icons(archive, folder, wanted):
    # Only PNGs explicitly named by the verified index. Never extract a tar path.
    if archive is None or not archive.is_file(): return
    st = archive.stat()
    if st.st_size > 20 * 1024 * 1024: return
    folder.mkdir(mode=0o700, parents=True, exist_ok=True)
    if folder.is_symlink(): return
    version = f"{st.st_size}:{st.st_mtime_ns}:{st.st_ctime_ns}"
    stamp = folder / ".stamp"
    if stamp.is_file() and not stamp.is_symlink() and stamp.read_text() == version: return
    with tarfile.open(archive, "r:*") as tf:
        for count, member in enumerate(tf):
            if count >= 8192: raise ValueError("AppStream icon index has too many members")
            name = member.name.removeprefix("./")
            if not member.isfile() or name not in wanted or not ICON.fullmatch(name): continue
            if not 24 <= member.size <= ICON_LIMIT: continue
            reader = tf.extractfile(member)
            if reader is None: continue
            raw = reader.read(ICON_LIMIT + 1)
            if (len(raw) != member.size or raw[:16] != b"\x89PNG\r\n\x1a\n\x00\x00\x00\x0dIHDR"
                or struct.unpack(">II", raw[16:24]) != (64, 64)): continue
            with tempfile.NamedTemporaryFile(dir=folder, prefix=".new-", delete=False) as f:
                f.write(raw)
            os.replace(f.name, folder / name)
    with tempfile.NamedTemporaryFile(dir=folder, mode="w", prefix=".new-", delete=False) as f:
        f.write(version)
    os.replace(f.name, stamp)

def local(record, key):
    value = record.get(key)
    if isinstance(value, dict): value = value.get("C", value.get("en", ""))
    return value if isinstance(value, str) else ""

def records(files):
    seen = set()
    loader = getattr(yaml, "CSafeLoader", yaml.SafeLoader)
    for path in files:
        if path.stat().st_size > 64 * 1024 * 1024:
            raise ValueError("AppStream index exceeds 64 MiB")
        with path.open(encoding="utf-8") as f:
            for d in yaml.load_all(f, Loader=loader):
                if not isinstance(d, dict) or d.get("Type") not in {"desktop-application", "desktop-app"}: continue
                package = d.get("Package")
                if isinstance(package, list): package = next(iter(package), None)
                if not isinstance(package, str) or not PKG.fullmatch(package): continue
                name = local(d, "Name")
                if not name: continue
                launchable = d.get("Launchable")
                desktop = launchable.get("desktop-id", []) if isinstance(launchable, dict) else []
                if not isinstance(desktop, list): desktop = [desktop]
                desktop = [x for x in [*desktop, d.get("ID")] if isinstance(x, str) and DESKTOP.fullmatch(x)]
                if (package, name) in seen: continue
                seen.add((package, name))
                cats = d.get("Categories")
                cats = [s for s in cats if isinstance(s, str)][:16] if isinstance(cats, list) else []
                yield {"package":package, "name":name[:100], "summary":local(d,"Summary")[:250], "desktop":sorted(set(desktop)), "categories":cats, "icon":cached_icon(d)}

def main():
    files = sorted(Path(p) for p in glob.glob("/var/lib/apt/lists/*_dists_trixie_main_dep11_Components-*.yml"))
    if not files: raise ValueError("No APT AppStream index. Refresh the signed repository.")
    rows = []
    for n, row in enumerate(records(files)):
        if n >= 6000: raise ValueError("Too many AppStream rows")
        rows.append(row)
    archives = sorted(Path(p) for p in glob.glob("/var/lib/apt/lists/*_dists_trixie_main_dep11_icons-64x64.tar"))
    wanted = {r["icon"] for r in rows if r["icon"]}
    try:
        sync_icons(next(iter(archives), None), Path("/run/droidnative/catalog-icons"), wanted)
    except (OSError, ValueError, tarfile.TarError):
        pass # Missing or unreadable icon cache must not hide an installable app.
    for row in rows:
        print(json.dumps(row, ensure_ascii=False))

if __name__ == "__main__":
    main()
