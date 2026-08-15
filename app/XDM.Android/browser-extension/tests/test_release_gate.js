"use strict";
const assert = require("assert");
const fs = require("fs");
const path = require("path");
const vm = require("vm");
const cryptoNode = require("crypto");
const os = require("os");
const childProcess = require("child_process");

const source = path.resolve(__dirname, "../src/main/extension/xdm-firefox");
const context = vm.createContext({
  console,
  URL,
  URLSearchParams,
  encodeURI,
  Date,
  setTimeout,
  clearTimeout,
  document: { baseURI: "https://page.example/watch", title: "Release fixture" },
  location: { href: "https://page.example/watch" },
  globalThis: null,
});
context.globalThis = context;
context.XdmExtensionConfig = {
  contractVersion: 2,
  xdmScheme: "xdmdownload",
  defaultTarget: "xdm",
};
for (const file of ["detector-core.js", "candidate-store.js", "handoff.js"]) {
  vm.runInContext(fs.readFileSync(path.join(source, file), "utf8"), context, { filename: file });
}

const core = context.XdmDetectorCoreV1;
const handoff = context.XdmHandoffV1;

const handoffSource = fs.readFileSync(path.join(source, "handoff.js"), "utf8");
const gradleSource = fs.readFileSync(path.resolve(__dirname, "../build.gradle.kts"), "utf8");
const prepareSource = fs.readFileSync(path.resolve(__dirname, "../tools/prepare_extension.py"), "utf8");
const verifySource = fs.readFileSync(path.resolve(__dirname, "../tools/verify_release_artifacts.py"), "utf8");
const cliSource = fs.readFileSync(path.resolve(__dirname, "../src/main/kotlin/com/mikeyphw/xdm/android/browserextension/BrowserExtensionPackageCli.kt"), "utf8");
assert(!handoffSource.includes('captureOaepHash || "SHA-256"'), "encrypted runtime must not guess OAEP");
assert(gradleSource.includes("requireReleaseCaptureKeyBinding(keyId, publicKey)"));
assert(prepareSource.includes("require_capture_key_binding(args.capture_key_id, args.capture_public_key_spki)"));
assert(prepareSource.includes("--capture-oaep-hash is required outside keyless debug rendering"));
assert(prepareSource.includes('if args.channel == "release":'));
assert(verifySource.includes("SHA-256(SPKI DER).take(24)"));
assert(cliSource.includes("captureKeyIdForSpki(capturePublicKeySpki)"));

const fakeSpki = "A".repeat(256);
const fakeSpkiDer = Buffer.from(fakeSpki, "base64url");
const fakeKeyId = cryptoNode.createHash("sha256").update(fakeSpkiDer).digest("hex").slice(0, 24);
const prepareScript = path.resolve(__dirname, "../tools/prepare_extension.py");
const tempRoot = fs.mkdtempSync(path.join(os.tmpdir(), "xdm-release-gate-"));
try {
  const common = [
    prepareScript, "--source", source, "--output", path.join(tempRoot, "matching"),
    "--channel", "release", "--default-target", "xdm",
    "--capture-key-id", fakeKeyId, "--capture-public-key-spki", fakeSpki, "--capture-oaep-hash", "SHA-256",
  ];
  const matching = childProcess.spawnSync("python3", common, { encoding: "utf8" });
  assert.strictEqual(matching.status, 0, matching.stderr || matching.stdout);

  const mismatchArgs = common.slice();
  mismatchArgs[mismatchArgs.indexOf(fakeKeyId)] = "0123456789abcdef01234567";
  mismatchArgs[mismatchArgs.indexOf(path.join(tempRoot, "matching"))] = path.join(tempRoot, "mismatch");
  const mismatched = childProcess.spawnSync("python3", mismatchArgs, { encoding: "utf8" });
  assert.notStrictEqual(mismatched.status, 0, "mismatched capture key/SPKI pair must fail closed");

  const noOaep = common.slice(0, -2);
  noOaep[noOaep.indexOf(path.join(tempRoot, "matching"))] = path.join(tempRoot, "no-oaep");
  const missingOaep = childProcess.spawnSync("python3", noOaep, { encoding: "utf8" });
  assert.notStrictEqual(missingOaep.status, 0, "release XDM packaging without explicit OAEP must fail closed");

  for (const target of ["ask", "1dm"]) {
    const keylessRelease = [
      prepareScript, "--source", source, "--output", path.join(tempRoot, `keyless-${target}`),
      "--channel", "release", "--default-target", target, "--capture-oaep-hash", "SHA-256",
    ];
    const result = childProcess.spawnSync("python3", keylessRelease, { encoding: "utf8" });
    assert.notStrictEqual(result.status, 0, `release ${target} packaging must remain capture-key bound`);
  }
} finally {
  fs.rmSync(tempRoot, { recursive: true, force: true });
}
for (const fixture of [
  { url: "https://cdn.example/video.mp4", type: "media", contentType: "video/mp4" },
  { url: "https://cdn.example/master", type: "fetch", contentType: "application/vnd.apple.mpegurl" },
  { url: "https://cdn.example/manifest", type: "xmlhttprequest", contentType: "application/dash+xml" },
]) assert(core.classifyResponse(fixture).accept, `release fixture rejected: ${fixture.contentType}`);
assert(!core.classifyResponse({ url: "https://cdn.example/chunk-11.m4s", type: "media", contentType: "video/mp4" }).accept);

const signedA = "https://cdn.example/master.m3u8?signature=signed-a&expires=999";
const signedB = "https://cdn.example/master.m3u8?signature=signed-b&expires=999";
const fpA = core.requestFingerprint({ url: signedA, requestId: "request-a", tabId: 4, frameId: 0, requestGeneration: 1 });
const fpB = core.requestFingerprint({ url: signedB, requestId: "request-b", tabId: 4, frameId: 0, requestGeneration: 1 });
assert.notStrictEqual(fpA, fpB, "signed requests must retain distinct browser request identity");
assert.notStrictEqual(core.stableMediaIdentity(signedA, fpA), core.stableMediaIdentity(signedB, fpB));
assert.strictEqual(handoff.buildXdmCapture({ url: signedA }), "", "release path must not emit plaintext capture v1");
assert.match(handoff.buildTargets({ url: signedA }).xdm, /^xdmdownload:\/\/add\?v=1&url=/, "direct add remains separate from encrypted media capture");
assert.strictEqual(handoff.buildOneDm({ url: "https://cdn.example/video.mp4" }), "idmdownload:https://cdn.example/video.mp4");
console.log("browser bridge release-gate JavaScript tests passed");
