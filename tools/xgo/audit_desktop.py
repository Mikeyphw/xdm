#!/usr/bin/env python3
from __future__ import annotations
import argparse, json
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]

def read(rel:str)->str:
    return (ROOT/rel).read_text(encoding='utf-8')

def require(text:str, needles:list[str], label:str, checks:list[str]):
    missing=[n for n in needles if n not in text]
    if missing: raise SystemExit(f'{label}: missing {missing}')
    checks.extend(f'{label}:{n}' for n in needles)

def forbid(text:str, needles:list[str], label:str, checks:list[str]):
    found=[n for n in needles if n in text]
    if found: raise SystemExit(f'{label}: forbidden {found}')
    checks.extend(f'{label}:forbid:{n}' for n in needles)

def main()->int:
    ap=argparse.ArgumentParser()
    ap.add_argument('--mode',required=True,choices=['engine-host','publication','platform-services'])
    ap.add_argument('--output',required=True)
    ns=ap.parse_args(); checks=[]
    host=read('app/XDM/src/XDM.Platform/DesktopGoEngineHost.cs')
    dispatcher=read('app/XDM/src/XDM.Platform/DesktopGoPlatformDispatcher.cs')
    broker=read('app/XDM/src/XDM.Platform/DesktopGoPublicationBroker.cs')
    app=read('app/XDM/src/XDM.App/App.axaml.cs')
    runtime=read('engine/runtime/desktop_host.go')
    platform=read('engine/runtime/platform/broker.go')
    if ns.mode=='engine-host':
        require(host,['xdm_engine_create','xdm_engine_command','xdm_engine_next_frame','xdm_engine_platform_reply','xdm_engine_metadata','xdm_engine_shutdown','xdm_buffer_free','abi_major','protocolMajor','go-desktop-state-v1.json','Native.xdm_engine_shutdown(_handle)'],'host',checks)
        require(app,['DesktopGoEngineHost','XGO-DESKTOP-GO-ENGINE','StartAsync()','StopAsync()'],'app',checks)
        forbid(host,['IDownloadManager','DownloadManager','QueueSchedulerRuntime','FfmpegService'],'host-policy',checks)
        for script in ['publish-one.sh','publish-modern.sh','smoke-package.sh']:
            require(read('app/XDM/eng/'+script),['build-xgo-native.sh'],script,checks)
        for script in ['publish-one.ps1','publish-modern.ps1','smoke-package.ps1']:
            require(read('app/XDM/eng/'+script),['build-xgo-native.ps1'],script,checks)
        require(read('app/XDM/eng/build-xgo-native.sh'),['-buildmode=c-shared','libxdmcore.so','XDM_XGO_NATIVE_DIR'],'native-sh',checks)
        require(read('app/XDM/eng/build-xgo-native.ps1'),['-buildmode=c-shared','xdmcore.dll','XDM_XGO_NATIVE_DIR'],'native-ps1',checks)
    elif ns.mode=='publication':
        require(runtime,['DesktopPublicationCommitKind','platform.Publication','desktop.publication.committed'],'go-publication',checks)
        require(broker,['go-publication-journal-v1.json','Status = "pending"','File.Move','existing.Status == "committed"','File.Exists(existing.Location) && !File.Exists(request.StagingPath)','CreateReceiptId','IdempotencyKey','existing?.Location ?? ResolveDestination','already bound to a different publication'],'desktop-publication',checks)
        forbid(broker,['DownloadRetryPolicy','QueueSchedulerRuntime','HttpClient'],'publication-policy',checks)
    else:
        require(platform,['Wakeup                 Kind = "wakeup"'],'platform-kind',checks)
        require(runtime,['DesktopSystemProxyKind','DesktopSecretLookupKind','DesktopWakeupKind','DesktopWakeDueKind','platform.SystemProxy','platform.SecretLookup','platform.Wakeup','desktop.secret.resolved'],'go-platform',checks)
        require(dispatcher,['"system_proxy"','"secret_lookup"','"wakeup"','HttpClient.DefaultProxy','JsonNamingPolicy.SnakeCaseLower','AutomaticConfigurationUrl','ServerCredentialResolver.Resolve','new Timer','ScheduleWakeupLocked','remaining > TimeSpan.Zero','WakeDue'],'desktop-platform',checks)
        require(host,['kind == "platform.request"','DispatchPlatformRequestAsync','continue;'],'host-private-platform',checks)
        forbid(dispatcher,['DownloadRetryPolicy','QueueSchedulerRuntime','SegmentedDownloadExecutor','Aria2Service'],'platform-policy',checks)
        forbid(runtime,['super-secret-desktop-token'],'go-secret-fixture-leak',checks)
    result={'schema_version':1,'status':'pass','mode':ns.mode,'check_count':len(checks),'checks':checks}
    out=ROOT/ns.output; out.parent.mkdir(parents=True,exist_ok=True); out.write_text(json.dumps(result,indent=2,sort_keys=True)+'\n')
    print(json.dumps(result,sort_keys=True))
    return 0
if __name__=='__main__': raise SystemExit(main())
