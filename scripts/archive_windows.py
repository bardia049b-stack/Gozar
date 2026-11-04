import pathlib
import sys
import zipfile

source, destination = map(pathlib.Path, sys.argv[1:3])
with zipfile.ZipFile(destination, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
    for path in sorted(source.rglob('*')):
        if path.is_file():
            archive.write(path, path.relative_to(source))
if destination.stat().st_size >= 17_000_000:
    for path in sorted(source.rglob('*'), key=lambda item: item.stat().st_size if item.is_file() else 0, reverse=True)[:15]:
        if path.is_file():
            print(path.relative_to(source), path.stat().st_size)
    raise SystemExit('Windows archive exceeds the 17 MB download limit')
