import os
import sys
import tarfile

app, destination = sys.argv[1:3]
with tarfile.open(destination, 'w:xz', preset=9) as archive:
    archive.add(app, arcname='Gozar.app')
if os.path.getsize(destination) >= 17_000_000:
    raise SystemExit('The macOS download exceeds the 17 MB limit')
