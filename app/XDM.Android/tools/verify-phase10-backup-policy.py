#!/usr/bin/env python3
from __future__ import annotations
import sys, xml.etree.ElementTree as ET
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
errors=[]
manifest=(ROOT/'app/src/main/AndroidManifest.xml').read_text(encoding='utf-8')
if 'android:allowBackup="false"' not in manifest: errors.append('manifest must keep allowBackup=false')
if 'android:dataExtractionRules="@xml/data_extraction_rules"' not in manifest: errors.append('manifest must reference data_extraction_rules')
if 'android:fullBackupContent="@xml/backup_rules"' not in manifest: errors.append('manifest must reference backup_rules')
for rel in ['app/src/main/res/xml/backup_rules.xml','app/src/main/res/xml/data_extraction_rules.xml']:
    text=(ROOT/rel).read_text(encoding='utf-8')
    for domain in ['root','file','database','sharedpref','external']:
        if f'domain="{domain}" path="."' not in text:
            errors.append(f'{rel} must exclude all of {domain}')
    for marker in ['xdm.db','checkpoints/','ownership/','journals/','recovery/','termux/','diagnostics/','support/','device-transfer','cloud-backup']:
        if rel.endswith('data_extraction_rules.xml') and marker not in text:
            errors.append(f'{rel} missing explicit {marker} exclusion/section')
if errors:
    print('Phase 10 backup policy validation failed:')
    print('\n'.join('- '+e for e in errors))
    sys.exit(1)
print('Phase 10 backup and device-transfer exclusion policy passed')
