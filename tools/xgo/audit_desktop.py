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
    ap.add_argument('--mode',required=True,choices=['engine-host','publication','platform-services','desktop-e2e'])
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
    elif ns.mode=='platform-services':
        require(platform,['Wakeup                 Kind = "wakeup"'],'platform-kind',checks)
        require(runtime,['DesktopSystemProxyKind','DesktopSecretLookupKind','DesktopWakeupKind','DesktopWakeDueKind','platform.SystemProxy','platform.SecretLookup','platform.Wakeup','desktop.secret.resolved'],'go-platform',checks)
        require(dispatcher,['"system_proxy"','"secret_lookup"','"wakeup"','HttpClient.DefaultProxy','JsonNamingPolicy.SnakeCaseLower','AutomaticConfigurationUrl','ServerCredentialResolver.Resolve','new Timer','ScheduleWakeupLocked','remaining > TimeSpan.Zero','WakeDue'],'desktop-platform',checks)
        require(host,['kind == "platform.request"','DispatchPlatformRequestAsync','continue;'],'host-private-platform',checks)
        forbid(dispatcher,['DownloadRetryPolicy','QueueSchedulerRuntime','SegmentedDownloadExecutor','Aria2Service'],'platform-policy',checks)
        forbid(runtime,['super-secret-desktop-token'],'go-secret-fixture-leak',checks)
    else:
        vm=read('app/XDM/src/XDM.App/ViewModels/MainWindowViewModel.cs')
        media_vm=read('app/XDM/src/XDM.App/ViewModels/MainWindowViewModel.MediaWorkflow.cs')
        projection=read('app/XDM/src/XDM.Platform/DesktopGoProjectionClient.cs')
        download_broker=read('app/XDM/src/XDM.Platform/DesktopGoDownloadExecutionBroker.cs')
        media_broker=read('app/XDM/src/XDM.Platform/DesktopGoMediaBroker.cs')
        go_ui=read('engine/runtime/desktop_ui_media.go')
        require(app,['DesktopGoProjectionClient','DesktopGoExecutionObserver','XGO-DESKTOP-PROJECTIONS','XGO-DESKTOP-EXECUTION-OBSERVATION'],'desktop-startup',checks)
        require(vm,['DesktopGoProjectionClient _desktopGo','ApplyDesktopGoProjection','DownloadProjectionChanged','ExecuteDownloadActionAsync("pause"','ExecuteDownloadActionAsync("resume"','ExecuteDownloadActionAsync("cancel"','ExecuteDownloadActionAsync("retry"','SubmitMediaCatalogToGoAsync','OnDesktopGoMediaProjectionChanged','ExecuteMediaAsync'],'avalonia-go-projection',checks)
        require(media_vm,['capture_record = captureRecord','credential_scope','desktop-handoff:','ApplyDesktopGoMediaProjection','Go media graph','RegisterMediaVariant','CaptureMediaAsync','SelectMediaAsync'],'browser-media-go-graph',checks)
        require(projection,['desktop.ui.snapshot','desktop.media.snapshot','desktop.ui.download_command','desktop.ui.execution_observation','desktop.media.capture','desktop.media.select','desktop.media.execute','SafeProjectionUrl'],'projection-client',checks)
        require(download_broker,['Transitional XGO-79 physical execution adapter','_downloads.AddAsync','_downloads.PauseAsync','_downloads.ResumeAsync','_downloads.CancelAsync','_downloads.RetryAsync'],'download-side-effects-only',checks)
        forbid(download_broker,['PreviewAdmissionAsync','DownloadRetryPolicy','QueueSchedulerRuntime','SetPriorityAsync','MoveToQueueAsync'],'download-policy',checks)
        require(media_broker,['desktop_media_execute','tool.GetString() != "ffmpeg"','finalize_adaptive','record_stream','ProcessStartInfo','ArgumentList.Add("-c")','request_handoff_missing'],'typed-media-broker',checks)
        forbid(media_broker,['UseShellExecute = true','cmd.exe','/bin/sh','HlsDownloader','DashDownloader','MediaDownloadService'],'media-policy',checks)
        require(dispatcher,['"desktop_download_command"','"external_media_tool"'],'desktop-dispatch',checks)
        require(host,['platformKind == "external_media_tool"','Task.Run(() => DispatchPlatformRequestAsync'],'nonblocking-media-frame-pump',checks)
        require(go_ui,['DesktopUICommandKind','DesktopUIObservationKind','DesktopMediaCaptureKind','DesktopMediaSelectKind','DesktopMediaExecuteKind','platform.DesktopDownloadCommand','platform.ExternalMediaTool','defaultDesktopMediaSelection'],'go-authority',checks)
        forbid(vm,['_mediaDownloadService.DownloadAsync'],'old-media-downloader',checks)
        forbid(vm,['_downloadManager.AddAsync','_downloadManager.PauseAsync','_downloadManager.ResumeAsync','_downloadManager.CancelAsync','_downloadManager.RetryAsync'],'old-primary-download-verbs',checks)
    result={'schema_version':1,'status':'pass','mode':ns.mode,'check_count':len(checks),'checks':checks}
    out=ROOT/ns.output; out.parent.mkdir(parents=True,exist_ok=True); out.write_text(json.dumps(result,indent=2,sort_keys=True)+'\n')
    print(json.dumps(result,sort_keys=True))
    return 0
if __name__=='__main__': raise SystemExit(main())
