// The lock itself: that the door is shut, that it shuts the *right* way for
// each kind of request, and that the two ways through it work.
//
// The failure this is really guarding against is subtler than "someone got in".
// It is a 401 landing somewhere that can't report one. The face tracker is
// pulled into a worker by `importScripts`, which cannot carry a header and
// reports a rejection only as "worker failed to load"; the main-thread fallback
// fails identically. A cookie that doesn't reach the worker therefore looks
// exactly like a tracking bug, so this test watches the tracker come up rather
// than trusting that the page rendered.
import { BASE, launch, launchAnonymous, api, session } from "./harness.mjs";
import http from "node:http";

// Claim first, before anything below tries to. Half of this file asserts what
// a server *with* a paired device does, and against a freshly wiped one the
// checks would otherwise race the harness for the claim link — which is how
// this test first failed: its own "the claim link is dead" case claimed the
// server, and every later test in the suite was locked out.
await session();

// `fetch` cannot be used to test the redirect: `Sec-Fetch-Mode` is a forbidden
// header name, and Node's implementation quietly overwrites whatever you set
// with `cors`. A request that claims to be a navigation has to be written out
// by hand.
function rawGet(path, headers) {
  return new Promise((resolve, reject) => {
    const url = new URL(BASE + path);
    const req = http.request(
      { host: url.hostname, port: url.port, path: url.pathname, method: "GET", headers },
      res => { res.resume(); resolve({ status: res.statusCode, location: res.headers.location }); }
    );
    req.on("error", reject);
    req.end();
  });
}

let fail = 0;
const check = (n, ok, x = "") => {
  console.log((ok ? "  PASS  " : "  FAIL  ") + n + (x ? "   " + x : ""));
  if (!ok) fail++;
};
const sleep = ms => new Promise(r => setTimeout(r, ms));

/* ------------------------------ the door ------------------------------ */

console.log("--- what an unpaired visitor sees ---");
{
  // A navigation is sent to the pairing page; everything else gets a 401 with
  // a JSON body. Sending a fetch to an HTML login page instead would surface
  // in the app as "couldn't reach the server" and paint the album as broken
  // images, which is a much worse afternoon than a clear 401.
  const nav = await rawGet("/app.html", { "Sec-Fetch-Mode": "navigate", Accept: "text/html" });
  check("a navigation is redirected to the pairing page",
        nav.status === 302 && /\/pair$/.test(nav.location || ""),
        nav.status + " -> " + nav.location);

  // Older browsers don't send Sec-Fetch-Mode at all, so the Accept header is
  // the fallback signal. The Portal's Chrome 138 sends both; this covers the
  // one that doesn't.
  const oldNav = await rawGet("/app.html", { Accept: "text/html,application/xhtml+xml" });
  check("a browser too old for Sec-Fetch-Mode is redirected too",
        oldNav.status === 302, oldNav.status + " -> " + oldNav.location);

  const list = await fetch(BASE + "/media/list");
  check("the album listing answers 401, not a redirect", list.status === 401, "status " + list.status);
  check("...and answers in JSON, so the app can read the refusal",
        /application\/json/.test(list.headers.get("content-type") || ""),
        list.headers.get("content-type"));

  const media = await fetch(BASE + "/media/pic-2026-01-01T00-00-00Z-aaaa.jpg");
  check("a capture cannot be fetched without a session", media.status === 401, "status " + media.status);

  const model = await fetch(BASE + "/models/blaze_face_short_range.tflite");
  check("even the models are behind the lock", model.status === 401, "status " + model.status);

  const pair = await fetch(BASE + "/pair");
  check("the pairing page itself is open", pair.status === 200, "status " + pair.status);

  const claim = await fetch(BASE + "/auth/claim", {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ secret: "test-only", name: "Impostor" })
  });
  check("the claim link is dead once a device is paired", claim.status === 403, "status " + claim.status);

  const approve = await fetch(BASE + "/auth/pair/approve", {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ code: "AAAAAA", name: "Impostor" })
  });
  check("approving requires a session of your own", approve.status === 401, "status " + approve.status);

  // `/s/%` used to take the whole server down: decodeURIComponent throws a
  // URIError on a malformed escape, and an exception out of the request
  // handler ends the process. One byte, no session, no more PortalSnap.
  const malformed = await rawGet("/s/%", {});
  check("a malformed escape is refused, not fatal", malformed.status >= 400, "status " + malformed.status);
  const stillUp = await fetch(BASE + "/pair");
  check("...and the server is still standing", stillUp.status === 200, "status " + stillUp.status);
}

/* -------------------- the tracker, through the cookie -------------------- */

console.log("\n--- a paired device can actually run the app ---");
{
  const b = await launch();
  const p = await b.newPage();
  let pageErrors = 0;
  p.on("pageerror", e => { console.log("    [pageerror] " + e.message); pageErrors++; });
  await p.setViewport({ width: 1280, height: 644 });
  await p.goto(BASE + "/app.html", { waitUntil: "domcontentloaded" });
  await p.waitForFunction(
    () => document.getElementById("loader").classList.contains("hidden"),
    { timeout: 60000 }
  );

  await p.click("#hudTap");
  await sleep(1500);
  const hud = await p.evaluate(() => {
    const o = {};
    for (const line of document.getElementById("hud").textContent.split("\n")) {
      const m = /^(\w+)\s+(.*)$/.exec(line.trim());
      if (m) o[m[1]] = m[2];
    }
    return o;
  });

  // This is the assertion the whole file exists for. "worker" means
  // `importScripts` reached the vendored MediaPipe bundle and the wasm beside
  // it — three cookie-bearing requests from a context that cannot send headers.
  check("the tracker is running in the worker, not the fallback",
        hud.backend === "worker", JSON.stringify(hud.backend));
  check("...and it is actually inferring", parseFloat(hud.infer) >= 0 && hud.infer !== undefined,
        "infer " + hud.infer);
  check("no page errors while authenticated", pageErrors === 0, pageErrors + " error(s)");
  await b.close();
}

/* ------------------------ pairing, both directions ------------------------ */

console.log("\n--- pairing a device that has no keyboard ---");
{
  // The Portal's half: an unpaired browser lands on the pairing page, which
  // shows a QR and a typed code and then waits.
  const portal = await launchAnonymous();
  const pp = await portal.newPage();
  await pp.setViewport({ width: 1280, height: 644 });
  await pp.goto(BASE + "/app.html", { waitUntil: "domcontentloaded" });
  check("an unpaired browser is taken to the pairing page",
        /\/pair$/.test(pp.url()), pp.url());

  await pp.waitForFunction(() => {
    const c = document.getElementById("code");
    return c && !c.classList.contains("hide") && c.textContent.length === 6;
  }, { timeout: 15000 });
  const code = await pp.$eval("#code", n => n.textContent.trim());
  check("it shows a six-character code", /^[0-9A-Z]{6}$/.test(code), code);

  const qrOk = await pp.$eval("#qr", n => n.innerHTML.length > 200 && !n.closest(".hide"));
  check("it renders a QR, server-side, with no library on the page", qrOk);

  // The phone's half: a device that is already trusted types the code in.
  const phone = await launch();
  const ph = await phone.newPage();
  await ph.setViewport({ width: 420, height: 900 });
  await ph.goto(BASE + "/pair", { waitUntil: "domcontentloaded" });
  await ph.waitForSelector("#codeform:not(.hide)", { timeout: 10000 });

  await ph.type("#codein", code.toLowerCase());   // typed in a hurry, in the wrong case
  await ph.click("#gocode");
  await ph.waitForFunction(() => /Approved/.test(document.getElementById("msg").textContent),
                           { timeout: 10000 });
  check("a paired phone can approve by typing the code", true);

  // ...and the Portal lets itself in, without anyone touching it.
  await pp.waitForFunction(() => !/\/pair/.test(location.pathname), { timeout: 20000 });
  await pp.waitForFunction(
    () => document.getElementById("loader") &&
          document.getElementById("loader").classList.contains("hidden"),
    { timeout: 60000 }
  );
  check("the waiting device signs itself in and reaches the camera", true, pp.url());

  await portal.close();
  await phone.close();
}

console.log("\n--- approving by scanning, and refusing a bad code ---");
{
  // What the QR encodes is a URL with the approval secret in its fragment. A
  // phone that scans it lands on the same page in approve mode.
  const started = await (await fetch(BASE + "/auth/pair/start", { method: "POST" })).json();
  const phone = await launch();
  const ph = await phone.newPage();
  await ph.setViewport({ width: 420, height: 900 });
  await ph.goto(started.approveUrl, { waitUntil: "domcontentloaded" });
  await ph.waitForSelector("#nameform:not(.hide)", { timeout: 10000 });
  await ph.$eval("#name", n => { n.value = "Scanned Portal"; });
  await ph.click("#go");
  await ph.waitForFunction(() => /Approved/.test(document.getElementById("msg").textContent),
                           { timeout: 10000 });

  const collected = await (await fetch(BASE + "/auth/pair/poll", {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ id: started.id, deviceSecret: started.deviceSecret })
  })).json();
  check("scanning the QR approves the waiting device",
        collected.ok && collected.device === "Scanned Portal", JSON.stringify(collected));

  // The approval secret and the polling secret are deliberately different, so
  // someone who merely photographs the screen cannot race the Portal for the
  // token once a parent approves.
  const another = await (await fetch(BASE + "/auth/pair/start", { method: "POST" })).json();
  const approvalSecret = another.approveUrl.split("#approve=")[1].split(".")[1];
  const stolen = await fetch(BASE + "/auth/pair/poll", {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ id: another.id, deviceSecret: approvalSecret })
  });
  check("what the QR carries cannot be used to collect the token",
        stolen.status === 404, "status " + stolen.status);

  const bad = await api("/auth/pair/approve", {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ code: "ZZZZZZ", name: "Nope" })
  });
  check("a wrong code is refused", bad.status === 403, "status " + bad.status);

  await phone.close();
}

/* ------------------------------ share links ------------------------------ */

console.log("\n--- share links, for someone with no device ---");
{
  const PIXEL = Buffer.from(
    "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAFElEQVR4AWP4z8Dwn4GBgYGJgYEBAA4AAv8d0EUAAAAASUVORK5CYII=",
    "base64");
  const up = await (await api("/media?ext=png", {
    method: "POST", headers: { "Content-Type": "image/png" }, body: PIXEL
  })).json();

  const minted = await (await api("/share", {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ name: up.name, days: 7 })
  })).json();
  check("a paired device can mint a link", /\/s\/[0-9a-f]+\./.test(minted.url || ""), minted.url);

  // The token has nothing to do with the filename, which is only a timestamp
  // and four characters and would be guessable on its own.
  check("the link does not contain the filename",
        !minted.url.includes(up.name.replace(/\.png$/, "")), minted.url);

  const open = await fetch(minted.url);
  check("it opens with no cookie at all",
        open.status === 200 && /image\/png/.test(open.headers.get("content-type") || ""),
        open.status + " " + open.headers.get("content-type"));
  check("...and is not left in a shared cache",
        /private/.test(open.headers.get("cache-control") || ""),
        open.headers.get("cache-control"));

  const ranged = await fetch(minted.url, { headers: { Range: "bytes=0-9" } });
  check("byte ranges still work, so a shared clip can scrub", ranged.status === 206,
        "status " + ranged.status);

  const tampered = await fetch(minted.url.slice(0, -1) + "X");
  check("a tampered token is refused", tampered.status === 404, "status " + tampered.status);

  const wrongFile = await api("/share", {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ name: "../server.js" })
  });
  check("a link cannot be minted for something outside the album",
        wrongFile.status === 400, "status " + wrongFile.status);

  // Deleting the capture takes its links with it, so the list of what is
  // reachable from outside stays true.
  await api("/media/" + up.name, { method: "DELETE" });
  const afterDelete = await fetch(minted.url);
  check("deleting the capture kills its link", afterDelete.status === 404,
        "status " + afterDelete.status);

  const shares = await (await api("/share")).json();
  check("and drops it from the list", !(shares.shares || []).some(s => s.file === up.name),
        JSON.stringify(shares.shares || []));
}

/* -------------------------- devices and revoking -------------------------- */

console.log("\n--- the device list ---");
{
  const before = await (await api("/auth/devices")).json();
  check("the harness sees itself in the list",
        before.devices.some(d => d.current), JSON.stringify(before.devices.map(d => d.name)));

  const invite = await (await api("/auth/invite", { method: "POST" })).json();
  check("a paired device can mint an invite", /\/pair#invite=/.test(invite.url || ""), invite.url);

  const guest = await fetch(BASE + "/auth/invite/redeem", {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      id: invite.url.split("#invite=")[1].split(".")[0],
      secret: invite.url.split("#invite=")[1].split(".").slice(1).join("."),
      name: "Invited phone"
    })
  });
  const guestCookie = (guest.headers.getSetCookie() || [])
    .map(c => /^psnap=([^;]*)/.exec(c)).filter(Boolean)[0];
  check("scanning an invite enrols a new device", guest.status === 200 && !!guestCookie,
        "status " + guest.status);

  const reuse = await fetch(BASE + "/auth/invite/redeem", {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      id: invite.url.split("#invite=")[1].split(".")[0],
      secret: invite.url.split("#invite=")[1].split(".").slice(1).join("."),
      name: "Second helping"
    })
  });
  check("an invite is single use", reuse.status === 403, "status " + reuse.status);

  const guestValue = decodeURIComponent(guestCookie[1]);
  const guestId = guestValue.split(".")[0];
  const revoked = await api("/auth/devices/revoke", {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ id: guestId })
  });
  check("a device can be revoked", revoked.status === 200, "status " + revoked.status);

  const afterRevoke = await fetch(BASE + "/media/list", {
    headers: { Cookie: "psnap=" + guestValue }
  });
  check("a revoked device is locked out immediately", afterRevoke.status === 401,
        "status " + afterRevoke.status);
}

console.log(fail ? "\n" + fail + " FAILURE(S)" : "\nauthorization OK");
process.exit(fail ? 1 : 0);
