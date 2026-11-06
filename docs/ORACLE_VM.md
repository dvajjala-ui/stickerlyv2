# The Oracle VM (HandNavigate collector)

Plain reference: which instance runs the collector, what is open, and what I'd need from you to drive it myself.

## The instance
| Field | Value |
| --- | --- |
| Cloud | Oracle Cloud (OCI), **Free Tier** account |
| Region | India West (**Mumbai**) |
| Instance name | **handnavigate-browser-core** |
| Shape | VM.Standard.E2.1.Micro (Always Free; ~1 GB RAM, 1 OCPU) |
| OS | Ubuntu 24.04 Minimal |
| State | Running |
| VCN | handnavigate-vcn (subnet `worker`) |
| Private IP | 10.0.0.202 |
| Public IP | 130.210.17.187 — **ephemeral** (changes if the instance stops/starts) |
| FQDN | handnavigate-browser-core.worker.handnavigatevcn.oraclevcn.com |
| Login user | `ubuntu` |

(The other VCN in the tenancy, `localai-vcn`, is a separate experiment and not part of this.)

## What runs on it
- `handnavigate-browser.service` — a **user** systemd service (`systemctl --user`), linger enabled so it survives
  logout. It runs the daily collection and the local control UI.
- The UI binds **127.0.0.1:47841 only** (loopback). It is not exposed publicly; you reach it through an SSH tunnel.
- Since 5 Nov it carries the Supabase credentials in `windows-agent/.env` on the VM and writes collected images to
  the shared Supabase project. That `.env` is the one file I still need a copy of (see below).
- A signed-in Chrome profile for Pinterest (and Reddit once earned) lives in the VM's data dir. Logins are done by a
  person over a VNC-over-SSH tunnel; they are never automated.

## What is open (network)
- **SSH (TCP 22):** allowed from **one IP only** — the workstation used to deploy it (per the deploy notes). From any
  other IP, SSH will time out until that IP is added to the subnet's security list.
- **Nothing else inbound.** No web port is public by design. Outbound internet is open (for Pinterest/Reddit/Supabase).

So to open it to a new machine: OCI Console → Networking → Virtual Cloud Networks → handnavigate-vcn → the `worker`
subnet → its Security List → add an ingress rule for TCP 22 from that machine's public IPv4 (`/32`).

## What I need from Oracle to run it myself
Only if you want me to drive the VM (run the topic "earn" step, pull updates, restart the service). All optional —
the VM already runs daily on its own.
1. **The SSH private key file** for `ubuntu@130.210.17.187` (the key created at deploy time, stored outside the repo).
   Tell me its path on this PC, or paste it into a file I name.
2. **This PC's IP must be the allowed one** (likely already true, since this is your workstation). If SSH times out,
   add this PC's public IP to the security list as above.
3. If the public IP has changed since the screenshot, the current one (OCI → Compute → Instances →
   handnavigate-browser-core → Public IP). Consider **reserving** the public IP so it stops changing.

## What I'd do over SSH (read-only-ish, nothing destructive)
```sh
ssh -i <key> ubuntu@<public-ip>
cd handNavigation && git pull                         # pick up migration 004 (new topics) + GIF support
windows-agent/.venv/bin/python -m <migrate>           # applies 004 to the DB (idempotent)
bash deploy/free-vm/earn-topics.sh                     # earn the new meme/GIF topics (needs the signed-in profile)
systemctl --user restart handnavigate-browser.service  # pick up new code
```
I will not open chats, change settings, or touch the browser profile beyond what collection needs.

## Memory / capacity note
This Micro shape has ~1 GB RAM — enough for the browser collector with its 2 GB swap file, **not** enough for a local
model. All AI (vision, captions) runs on Groq from StickerDock's worker, not on the VM. If we later want more
headroom, an Always Free **A1 (Arm, up to 4 OCPU / 24 GB)** instance is the upgrade, subject to Mumbai capacity.
