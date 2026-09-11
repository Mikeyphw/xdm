"use strict";
const assert=require("assert"),fs=require("fs"),vm=require("vm"),path=require("path");
const source=path.resolve(__dirname,"../src/main/extension/xdm-firefox");
const context=vm.createContext({console,URL,URLSearchParams,encodeURI,document:{baseURI:"https://page.example/watch",title:"Episode 4"},location:{href:"https://page.example/watch"},globalThis:null}); context.globalThis=context;
context.XdmExtensionConfig={contractVersion:3,xdmScheme:"xdmdownload",defaultTarget:"xdm"}; vm.runInContext(fs.readFileSync(path.join(source,"handoff.js"),"utf8"),context,{filename:"handoff.js"});
const handoff=context.XdmHandoffV1;
const capture=handoff.buildXdmCapture({url:"https://cdn.example/master.m3u8?token=abc",pageUrl:"https://page.example/watch",mimeType:"application/vnd.apple.mpegurl",stableMediaId:"browser-media-12345678",sessionRevision:9,browserHandoff:{proposedHeaders:{headers:{Referer:"https://page.example/watch",Cookie:"sid=abc"}},finalHeaders:{headers:{Authorization:"Bearer xyz","User-Agent":"IronFox"}}}});
assert.match(capture,/^xdmdownload:\/\/capture\?v=3&/); const uri=new URL(capture); assert.strictEqual(uri.searchParams.get("url"),"https://cdn.example/master.m3u8?token=abc"); assert.strictEqual(uri.searchParams.get("mime"),"application/vnd.apple.mpegurl"); assert(uri.searchParams.get("headers").includes("authorization: Bearer xyz")); assert(uri.searchParams.get("proposedHeaders").includes("cookie: sid=abc"));
const targets=handoff.buildTargets({url:"https://cdn.example/master.m3u8?token=abc"}); assert.match(targets.xdm,/^xdmdownload:\/\/add\?v=1&url=/); assert.strictEqual(handoff.buildOneDm({url:"https://cdn.example/video.mp4"}),"idmdownload:https://cdn.example/video.mp4");
console.log("direct v3 single-candidate handoff tests passed");

(async()=>{
  const session=await handoff.buildCaptureSession({
    sessionId:"tab-7-session", revision:42, pageUrl:"https://page.example/watch", title:"Episode 4", totalCandidateCount:2,
    candidates:[
      {url:"https://cdn.example/master.m3u8?token=abc",canonicalUrl:"https://cdn.example/master.m3u8",logicalMediaId:"logical-master-12345678",contentType:"application/vnd.apple.mpegurl",requestFingerprint:"request-one-12345678",stableMediaId:"media-one-12345678",manifest:true,manifestRole:"master",confidence:1110,observationCount:28,segmentCount:25,encryptedAes128:true,variantInfo:[{url:"https://cdn.example/720.m3u8",bandwidth:2800000,width:1280,height:720,codecs:"avc1.64001f",audioGroup:"audio"}],trackInfo:[{url:"https://cdn.example/en.vtt",type:"subtitles",groupId:"subs",name:"English",language:"en",default:true}],browserHandoff:{finalHeaders:{headers:{Referer:"https://page.example/watch",Authorization:"Bearer one"}}}},
      {url:"https://cdn2.example/video.mp4?token=def",contentType:"video/mp4",durationMs:630000,thumbnailUrl:"https://img.example/poster.jpg",requestFingerprint:"request-two-12345678",stableMediaId:"media-two-12345678",playbackObserved:true,browserHandoff:{proposedHeaders:{headers:{Referer:"https://page.example/watch",Cookie:"sid=two"}}}}
    ]
  });
  const sessionUri=new URL(session);
  assert.strictEqual(sessionUri.searchParams.get("sid"),"tab-7-session");
  assert.strictEqual(sessionUri.searchParams.get("candidateCount"),"2");
  assert.strictEqual(sessionUri.searchParams.get("capturedCandidateCount"),"2");
  assert.strictEqual(sessionUri.searchParams.get("truncated"),"0");
  const batch=JSON.parse(sessionUri.searchParams.get("candidates"));
  assert.strictEqual(batch.length,2);
  assert.strictEqual(batch[1].url,"https://cdn2.example/video.mp4?token=def");
  assert.strictEqual(batch[0].finalHeaders.authorization,"Bearer one");
  assert.strictEqual(batch[0].logicalMediaId,"logical-master-12345678");
  assert.strictEqual(batch[0].canonicalUrl,"https://cdn.example/master.m3u8");
  assert.strictEqual(batch[0].manifestRole,"master");
  assert.strictEqual(batch[0].observationCount,28);
  assert.strictEqual(batch[0].segmentCount,25);
  assert.strictEqual(batch[0].encryptedAes128,true);
  assert.strictEqual(batch[0].variantInfo[0].height,720);
  assert.strictEqual(batch[0].trackInfo[0].language,"en");
  assert.strictEqual(batch[1].proposedHeaders.cookie,"sid=two");
  assert.strictEqual(batch[1].durationMs,630000);
  assert.strictEqual(batch[1].thumbnailUrl,"https://img.example/poster.jpg");
  assert.strictEqual(batch[1].title,"Episode 4");
  console.log("bounded direct v3 capture-session tests passed");
})().catch(error=>{ console.error(error); process.exitCode=1; });
