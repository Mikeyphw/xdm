"use strict";
const assert = require("assert");
const fs = require("fs");
const path = require("path");
const vm = require("vm");
const { webcrypto } = require("crypto");
const { TextEncoder, TextDecoder } = require("util");

function b64url(bytes) {
  return Buffer.from(bytes).toString("base64").replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}
function fromB64url(value) {
  const raw = String(value).replace(/-/g, "+").replace(/_/g, "/");
  return Buffer.from(raw + "=".repeat((4 - raw.length % 4) % 4), "base64");
}

async function runSecureHandoff(oaepHash) {
  const pair = await webcrypto.subtle.generateKey(
    { name: "RSA-OAEP", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: oaepHash },
    true,
    ["encrypt", "decrypt"],
  );
  const spki = new Uint8Array(await webcrypto.subtle.exportKey("spki", pair.publicKey));
  const keyId = `secure-test-${oaepHash.toLowerCase().replace(/[^a-z0-9]/g, "")}`;
  const source = path.resolve(__dirname, "../src/main/extension/xdm-firefox");
  const context = vm.createContext({
    console, URL, URLSearchParams, Date, TextEncoder, TextDecoder,
    crypto: webcrypto, Buffer,
    document: { baseURI: "https://page.example/watch", title: "Capture fixture" },
    location: { href: "https://page.example/watch" },
    globalThis: null,
  });
  context.globalThis = context;
  context.XdmExtensionConfig = {
    contractVersion: 2,
    xdmScheme: "xdmdownload",
    defaultTarget: "xdm",
    captureKeyId: keyId,
    capturePublicKeySpki: b64url(spki),
    captureOaepHash: oaepHash,
  };
  vm.runInContext(fs.readFileSync(path.join(source, "handoff.js"), "utf8"), context, { filename: "handoff.js" });

  const mediaUrl = "https://cdn.example/master.m3u8?signature=very-secret-signed-value";
  const link = await context.XdmHandoffV1.buildEncryptedCaptureSession({
    sessionId: "browser-7-secure-session",
    revision: 123456,
    pageUrl: "https://page.example/watch",
    title: "Capture fixture",
    candidates: [
      {
        url: mediaUrl,
        contentType: "application/vnd.apple.mpegurl",
        manifest: true,
        playbackObserved: true,
        stableMediaId: "media-1",
        reason: "webRequest-manifest",
        browserHandoff: {
          proposedHeaders: { headers: { Cookie: "session=secret-cookie", Referer: "https://page.example/watch" } },
          finalHeaders: { headers: { Authorization: "Bearer top-secret", "User-Agent": "IronFox" } },
        },
      },
      {
        url: "https://cdn.example/video.mp4",
        contentType: "video/mp4",
        stableMediaId: "media-2",
        reason: "playback",
      },
    ],
    totalCandidateCount: 2,
  });

  assert(link.startsWith("xdmdownload://capture?"));
  assert(!link.includes("secret-cookie"));
  assert(!link.includes("top-secret"));
  assert(!link.includes("very-secret-signed-value"));
  assert(!link.includes("cdn.example"));
  const uri = new URL(link);
  assert.deepStrictEqual([...uri.searchParams.keys()].sort(), ["ct", "ek", "iv", "kid", "sid", "v"]);
  assert.strictEqual(uri.searchParams.get("v"), "2");
  assert.strictEqual(uri.searchParams.get("sid"), "browser-7-secure-session");
  assert.strictEqual(uri.searchParams.get("kid"), keyId);

  const wrapped = fromB64url(uri.searchParams.get("ek"));
  const aesRaw = await webcrypto.subtle.decrypt({ name: "RSA-OAEP" }, pair.privateKey, wrapped);
  const aesKey = await webcrypto.subtle.importKey("raw", aesRaw, "AES-GCM", false, ["decrypt"]);
  const aad = new TextEncoder().encode(`xdm-capture-v2|browser-7-secure-session|${keyId}`);
  const clear = await webcrypto.subtle.decrypt(
    {
      name: "AES-GCM",
      iv: fromB64url(uri.searchParams.get("iv")),
      additionalData: aad,
      tagLength: 128,
    },
    aesKey,
    fromB64url(uri.searchParams.get("ct")),
  );
  const payload = JSON.parse(new TextDecoder().decode(clear));
  assert.strictEqual(payload.candidates.length, 2, "full bounded candidate set survives encrypted handoff");
  assert.strictEqual(payload.candidates[0].url, mediaUrl);
  assert.strictEqual(payload.candidates[0].proposedHeaders.cookie, "session=secret-cookie");
  assert.strictEqual(payload.candidates[0].finalHeaders.authorization, "Bearer top-secret");
  assert.strictEqual(payload.totalCandidateCount, 2);
  assert.strictEqual(payload.truncated, false);
}

(async () => {
  await runSecureHandoff("SHA-256");
  await runSecureHandoff("SHA-1");
  console.log("secure capture-session handoff tests passed for SHA-256 and SHA-1");
})().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
