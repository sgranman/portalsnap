# Security

PortalSnap runs a camera in a room with children in it and keeps the resulting
photos on a server you host. That deserves a plain statement of what it does and
does not protect.

## Reporting a vulnerability

Open a [private security advisory](https://github.com/sgranman/portalsnap/security/advisories/new)
rather than a public issue. This is a hobby project maintained in spare time —
expect a considered reply, not a fast one.

## What this stores, and where

| | |
|---|---|
| `media/` | every photo and clip ever kept, as plain files on disk |
| `data/` | the device list and live share links |
| `reports/` | capability reports POSTed by the diagnostic pages |

All three are gitignored and must stay that way. **Nothing is encrypted at
rest.** Anyone with filesystem or backup access to the host has the album.

Nothing is sent anywhere else. There is no analytics, no telemetry, no external
API, and no third-party host contacted at runtime — which is the reason
MediaPipe and its models are vendored into the repo rather than loaded from a
CDN.

## How access works

There are no passwords anywhere. Every trusted device holds a random 256-bit
token in a cookie, and only the SHA-256 of that token is ever stored. A device
gets a token in exactly one of three ways: the one-time **claim** secret printed
to the server log while nothing is paired yet, a **QR pairing** approved by an
already-trusted device, or a short-lived **invite**.

Every route except `/pair` requires a trusted device. There is deliberately no
way to switch authorization off, and no "trust the local network" or "skip auth
on loopback" shortcut — the common deployment puts a proxy in front, at which
point every request arrives from a single source address and any such shortcut
would disable the lock entirely.

`/devices.html` renames and revokes devices and kills live share links. A
revoked device is locked out on its next request.

## Share links are bearer URLs — read this one

A share link (`/s/<token>`) serves one photo or clip to **anyone holding the
URL**, with no device and no login, for up to 30 days.

That is the entire point of the feature — it is how a photo reaches a
grandparent — but it means a share link is as sensitive as the photo behind it.
Forwarded, screenshotted, or sitting in someone's chat history, it still works
until it expires or you kill it.

Share links are **on by default**. To turn them off entirely:

```yaml
environment:
  SHARE_LINKS: "off"      # refuse to mint links that work without a device
  SHARE_MAX_DAYS: 7       # or just shorten their lifetime
```

If you are deploying this for a family, decide about this setting deliberately
rather than inheriting the default.

## Deploying it safely

- **Understand that the documented setup is internet-facing.** A `cloudflared`
  quick tunnel publishes this to anyone who learns the hostname. That is a
  deliberate choice rather than an oversight — the authorization here was built
  against exactly that threat, which is why every route except `/pair` requires
  a paired device and why no loopback bypass exists. But it does mean the only
  thing between a stranger and the camera page is a 256-bit token. If that is
  not a trade you want, terminate TLS on your own LAN instead (Caddy's
  `tls internal` is the least work) and keep the server off the public
  internet entirely.
- The server **never terminates TLS.** Put a proxy in front. It also never sets
  `Secure` on a cookie it is about to send over plain HTTP, because a browser
  silently discards that cookie and the symptom is a login that will not stick.
  Set `PORTALSNAP_SECURE_COOKIE=1` for a proxy that does not send
  `X-Forwarded-Proto`.
- Rate limiting is counted **server-wide, not per-IP**, on purpose: behind a
  proxy every request shares one source address, so per-IP limiting would either
  do nothing or lock out the whole household at once.
- Back up `data/auth.json` or accept that losing it means re-pairing every
  device. Losing every trusted device is recoverable: delete that file and the
  server goes back to printing a claim link on restart.

## Diagnostic pages are off by default

The capability probe, the tracker sweeps and the recording harness are not
served unless the server is started with `PORTALSNAP_DIAG=1`. They sit behind
the same pairing as everything else, so this is not a security boundary — but
each one opens a camera of its own, and a normal installation has no reason to
expose them.

## Scope

This is a personal project, not a hardened product. It has had no external
security review. The threat model it was built against is "someone on the open
internet who knows the hostname" — not a determined attacker already inside your
network, and not a malicious guest holding a trusted device.
