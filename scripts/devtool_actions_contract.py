#!/usr/bin/env python3
"""Read-only local/hosted Devtool source & dependency-graph contract; no tool downloads."""
from pathlib import Path
import tomllib, json, sys
root=Path(__file__).resolve().parents[1]
config=tomllib.loads((root/'.devtool.toml').read_text(encoding='utf-8'))
assert config.get('wrapper',{}).get('schema')==2, 'wrapper schema 2 required'
assert config['targets'] and 'ci-preflight' in config['wrapper']['commands']
for name,entry in config['wrapper']['commands'].items():
    if name.startswith('ci-'):
        target=entry['target'];wf=entry['workflow']
        assert wf in config['targets'][target]['workflows'],(name,target,wf)
for name,entry in config['targets'].items():
    for wf,steps in entry.get('workflows',{}).items():
        if not steps or not isinstance(steps[0],dict):continue
        found=set()
        for step in steps:
            assert step['id'] not in found,(name,wf,'duplicate',step['id'])
            assert set(step['depends_on'])<=found,(name,wf,'bad dependency',step)
            found.add(step['id'])
if root.name=='sims3tools':
    expected={'meshtoolkit_avalonia_MeshToolkit_Modern': 'meshtoolkit_avalonia/MeshToolkit.Modern.sln', 's3mo_s3mo_s3mo': 's3mo/s3mo/s3mo.sln', 's3mo_s3molib_s3molib': 's3mo/s3molib/s3molib.sln', 's3oc_modern_S3oc_Modern': 's3oc_modern/S3oc.Modern.sln', 's3atelier_avalonia_S3Atelier_Modern': 's3atelier_avalonia/S3Atelier.Modern.sln', 's3pe_avalonia_S3pe_Modern': 's3pe_avalonia/S3pe.Modern.sln', 's3ee_avalonia_S3ee_Modern': 's3ee_avalonia/S3ee.Modern.sln', 's3pi_base_S3pi_Base': 's3pi_base/S3pi.Base.sln', 'skinnator_avalonia_Skininator_Modern': 'skinnator_avalonia/Skininator.Modern.sln', 'skintone_tools_SkinTone_Tools': 'skintone_tools/SkinTone.Tools.sln', 'pattern_creator_modern': 'pattern_creator_avalonia/PatternCreator.Modern.sln'}
    for target,path in expected.items():
        assert (root/path).is_file(),(target,path)
        assert config['targets'][target]['solution']==path
    assert len(config['targets'])>=12
else:
    assert len(config['targets'])>=42
    assert (root/'engine/go.mod').is_file()
    assert 'xgo_foundation' in config['targets']
assert list((root/'.github/workflows').glob('*.yml'))
print(json.dumps({'status':'PASS','targets':len(config['targets']),'ci_wrappers':sorted(k for k in config['wrapper']['commands'] if k.startswith('ci-'))},sort_keys=True))
