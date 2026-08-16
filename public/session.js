// What a page does when its session stops being valid, in one place.
//
// A device can be revoked from another phone, or the server's device list can
// be wiped, and the first the app hears of it is a 401 on something ordinary:
// listing the album, uploading a photo, backfilling a preview frame. Several of
// those call sites already swallow their errors on purpose — a poster that
// fails to upload is not worth interrupting a child for — so without this the
// visible symptom of being signed out would be an album that quietly stops
// filling in.
//
// Wrapping `fetch` catches every one of them, including the ones written before
// there was any such thing as a session. Worker and worklet loads can't be
// caught here, but those fail loudly on their own.
(function () {
  const real = window.fetch.bind(window);

  window.fetch = async function (input, init) {
    const res = await real(input, init);
    if (res.status === 401 && !location.pathname.startsWith("/pair")) {
      // Same-origin only: a 401 from somewhere else is that server's business.
      let sameOrigin = true;
      try {
        const url = new URL(typeof input === "string" ? input : input.url, location.href);
        sameOrigin = url.origin === location.origin;
      } catch (e) { /* a Request we can't read is ours by default */ }
      if (sameOrigin) location.replace("/pair");
    }
    return res;
  };

  // Mints a link to one capture that works without a paired device. Returns the
  // URL, or null if this server has share links switched off.
  window.shareLink = async function (name, days) {
    try {
      const res = await fetch("/share", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ name, days: days || 7 })
      });
      if (!res.ok) return null;
      const out = await res.json();
      return out.url || null;
    } catch (e) { return null; }
  };
})();
