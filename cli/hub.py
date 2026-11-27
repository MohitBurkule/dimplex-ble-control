#!/usr/bin/env python3
"""Heater hub: one always-on machine (Raspberry Pi / laptop) owns the Bluetooth link to every heater
and serves a web page + JSON API on the LAN / tailnet. Phones talk to the hub, never to the heaters,
so the heaters' one-connection limit is never contended.

  uv run --with dbus-fast --with aiohttp cli/hub.py --config heaters.json --port 8080 [--sim]

API:
  GET  /api/status                      latest reading per heater
  GET  /api/history?heater=Kitchen&hours=24
  POST /api/heater/<label>/temp   {"temp": 21}
  POST /api/heater/<label>/mode   {"mode": "eco"|"manual"|"frost"|"timer"|"off"}
  POST /api/heater/<label>/refresh
"""
import argparse, asyncio, json, os, sqlite3, sys, time
from pathlib import Path

from aiohttp import web

sys.path.insert(0, str(Path(__file__).resolve().parent))
import dimplex as d  # noqa: E402

PAGE = """<!doctype html><html><head><meta name=viewport content="width=device-width,initial-scale=1">
<title>Heaters</title><style>
body{font-family:system-ui,sans-serif;margin:0;padding:12px;background:#f4f4f2;color:#222}
@media(prefers-color-scheme:dark){body{background:#151515;color:#eee}.c{background:#232323!important}}
.c{background:#fff;border-radius:12px;padding:14px;margin-bottom:12px}
h2{margin:0 0 4px;font-size:1.2em}.big{font-size:2.2em;font-weight:600}.m{opacity:.7;font-size:.9em}
button{font-size:1em;padding:8px 12px;margin:4px 4px 0 0;border-radius:8px;border:1px solid #888;background:none;color:inherit}
button.on{background:#2b6cb0;color:#fff;border-color:#2b6cb0}.err{color:#c53030}</style></head><body>
<div id=h></div><p class=m id=u></p><script>
const M={1:'timer',2:'manual',3:'eco',4:'frost'};
async function post(l,p,b){await fetch(`/api/heater/${encodeURIComponent(l)}/${p}`,{method:'POST',body:JSON.stringify(b||{})});load()}
async function load(){const s=await (await fetch('/api/status')).json();
document.getElementById('h').innerHTML=s.heaters.map(x=>{const r=x.reading||{};const mode=r.mode||'?';
const btn=(m,t)=>`<button class="${mode==m?'on':''}" onclick="post('${x.label}','mode',{mode:'${m}'})">${t}</button>`;
return `<div class=c><h2>${x.label}</h2>
<div class=big>${r.room!=null?r.room.toFixed(1)+'°C':'–'}</div>
<div class=m>avg 24h ${x.avg24!=null?x.avg24.toFixed(1)+'°C':'–'} · ${mode=='off'?'Off':'target '+(r.target??'?')+'°C · '+mode}
${r.heating?' · <b>heating</b> ~'+(r.watts||0)+' W':''}</div>
<div class=m>${x.age!=null?'updated '+Math.round(x.age/60)+' min ago':''} ${x.error?'<span class=err>'+x.error+'</span>':''}</div>
<button onclick="post('${x.label}','temp',{temp:${(r.target||18)}-1})">−</button>
<button onclick="post('${x.label}','temp',{temp:${(r.target||18)}+1})">+</button>
${btn('manual','Manual')}${btn('eco','Eco')}${btn('frost','Frost')}${btn('off','Off')}
<button onclick="post('${x.label}','refresh')">↻</button></div>`}).join('');
document.getElementById('u').textContent='hub '+s.hub+' · polled every '+s.interval+'s'}
load();setInterval(load,15000)</script></body></html>"""


class Hub:
    def __init__(self, heaters, sim, interval):
        self.heaters, self.sim, self.interval = heaters, sim, interval
        self.latest, self.errors = {}, {}
        self.lock = asyncio.Lock()   # one BLE operation at a time
        self.bz = None

    async def start(self):
        backend = d.Bluez
        if self.sim:
            from simbackend import SimBluez as backend
        self.bz = await backend(self.heaters).__aenter__()

    async def op(self, h, fn):
        async with self.lock:
            hz = await self.bz.ensure_connected(h)
            try:
                if fn:
                    await fn(hz); await asyncio.sleep(1)
                s = await hz.status()
            finally:
                await hz.disconnect()
        d.save(s)
        self.latest[h["label"]] = s
        self.errors.pop(h["label"], None)
        if self.sim:
            await self.bz.__aexit__()
        return s

    async def poll_forever(self):
        while True:
            for h in self.heaters:
                try:
                    await self.op(h, None)
                except Exception as e:
                    self.errors[h["label"]] = str(e)[:120]
            await asyncio.sleep(self.interval)

    def find(self, label):
        for h in self.heaters:
            if h["label"].lower() == label.lower():
                return h
        raise web.HTTPNotFound(text=f"unknown heater {label}")


def avg24(label):
    with d.db() as c:
        r = c.execute("select avg(room) from readings where label=? and ts>=?",
                      (label, int(time.time()) - 86400)).fetchone()
    return r[0]


def make_app(hub):
    app = web.Application()

    async def page(_):
        return web.Response(text=PAGE, content_type="text/html")

    async def status(_):
        now = int(time.time())
        return web.json_response({"hub": os.uname().nodename, "interval": hub.interval, "heaters": [
            {"label": h["label"], "reading": hub.latest.get(h["label"]), "error": hub.errors.get(h["label"]),
             "age": now - hub.latest[h["label"]]["ts"] if h["label"] in hub.latest else None,
             "avg24": avg24(h["label"])} for h in hub.heaters]})

    async def history(req):
        label = hub.find(req.query.get("heater", ""))["label"]
        since = int(time.time() - float(req.query.get("hours", 24)) * 3600)
        with d.db() as c:
            rows = c.execute("select ts,mode,target,room,heating,rssi from readings where label=? and ts>=? "
                             "order by ts", (label, since)).fetchall()
        return web.json_response([dict(zip(("ts", "mode", "target", "room", "heating", "rssi"), r)) for r in rows])

    async def act(req):
        h = hub.find(req.match_info["label"])
        body = await req.json() if req.can_read_body else {}
        what = req.match_info["what"]
        if what == "temp":
            t = int(body["temp"])
            fn = lambda hz: hz.set_target(t)
        elif what == "mode":
            m = str(body["mode"]).lower()
            if m not in d.MODE_IDS:
                raise web.HTTPBadRequest(text=f"mode must be one of {list(d.MODE_IDS)}")
            fn = lambda hz: hz.set_mode(m)
        elif what == "refresh":
            fn = None
        else:
            raise web.HTTPNotFound()
        try:
            return web.json_response(await hub.op(h, fn))
        except Exception as e:
            hub.errors[h["label"]] = str(e)[:120]
            raise web.HTTPBadGateway(text=str(e))

    app.add_routes([web.get("/", page), web.get("/api/status", status), web.get("/api/history", history),
                    web.post("/api/heater/{label}/{what}", act)])
    return app


async def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--config")
    ap.add_argument("--port", type=int, default=8080)
    ap.add_argument("--interval", type=int, default=120, help="seconds between polling rounds")
    ap.add_argument("--sim", action="store_true", default=bool(os.environ.get("DIMPLEX_SIM")))
    a = ap.parse_args()
    hub = Hub(d.load_config(a.config), a.sim, a.interval)
    await hub.start()
    runner = web.AppRunner(make_app(hub)); await runner.setup()
    await web.TCPSite(runner, "0.0.0.0", a.port).start()
    print(f"hub on http://{os.uname().nodename}:{a.port}  ({len(hub.heaters)} heaters{', SIM' if a.sim else ''})", flush=True)
    await hub.poll_forever()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
