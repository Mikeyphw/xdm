#!/usr/bin/env python3
"""XGO hosted Android source policy static validator."""
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
source=(ROOT/'.github/workflows/android.yml').read_text(encoding='utf-8')
assert source.count('      - uses: android-actions/setup-android@be39fa834029ff78f1a44aa3bb0819b8fc2bd8fd\n')==2
assert source.count("          packages: 'platform-tools'")==2
assert 'setup-android@v3' not in source
assert 'setup-android@v4' not in source
assert 'inputs.signed_release' in source
assert "startsWith(github.ref, 'refs/tags/android-v')" in source
print('XGO_ANDROID_SDK_SOURCE_PASS')
