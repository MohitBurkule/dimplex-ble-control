#!/usr/bin/env python3
"""Control Dimplex PLX Bluetooth heaters from a Linux terminal (BlueZ over D-Bus).

Config: JSON with labels, addresses and pairing PINs (see heaters.example.json).
Looked up at --config, $DIMPLEX_CONFIG, ~/.config/dimplex/heaters.json, ./heaters.json.
"""
import argparse, asyncio, json, os, sqlite3, sys, time
from pathlib import Path

from dbus_fast import BusType, Variant
from dbus_fast.aio import MessageBus
from dbus_fast.service import ServiceInterface, method

BLUEZ = "org.bluez"
ADAPTER = "/org/bluez/hci0"
AGENT_PATH = "/com/mohit/dimplex/agent"
DB_PATH = Path(os.environ.get("DIMPLEX_DB", Path.home() / ".local/share/dimplex/history.db"))

MODES = {1: "timer", 2: "manual", 3: "eco", 4: "frost"}
MODE_IDS = {v: k for k, v in MODES.items()} | {"off": 1}

def cu(x):  # characteristic UUID from short id
    return f"0000{x:04x}-0000-1000-8000-00805f9b34fb"

C_MODE, C_TARGET, C_RANGE, C_ROOM, C_TRAC, C_MODEL, C_ERROR = (
    cu(0x1001), cu(0x1023), cu(0x1006), cu(0x0008), cu(0x0002), cu(0x2005), cu(0x1011))
C_CLOCK = cu(0x0006)
C_SCHEDULE, C_POWER = cu(0x1002), cu(0x000a)
# True off = User Timer whose only slots are 1-min 7 °C slots at 03:00-03:04
OFF_SLOTS = [b for i in range(4) for b in (3, i, 3, i + 1, 7, 0)]


# ---------- config ----------
def load_config(path=None):
    cands = [path, os.environ.get("DIMPLEX_CONFIG"),
             Path.home() / ".config/dimplex/heaters.json", Path("heaters.json")]
    for c in cands:
        if c and Path(c).is_file():
            return json.loads(Path(c).read_text())["heaters"]
    sys.exit("No heaters config found (see heaters.example.json)")


def pick(heaters, name):
    if name in (None, "all"):
        return heaters
    m = [h for h in heaters if h["label"].lower() == name.lower()
         or h["address"].lower() == name.lower()]
    if not m:
        sys.exit(f"Unknown heater '{name}'. Known: {', '.join(h['label'] for h in heaters)}")
    return m


def dev_path(addr):
    return f"{ADAPTER}/dev_{addr.upper().replace(':', '_')}"


# ---------- pairing agent: answers the passkey request with the PIN from config ----------
class PinAgent(ServiceInterface):
    def __init__(self, pins):
        super().__init__("org.bluez.Agent1")
        self.pins = pins  # dev path -> pin

    @method()
    def RequestPasskey(self, device: "o") -> "u":
        pin = self.pins.get(device)
        if pin is None:
            raise Exception("org.bluez.Error.Rejected")
        return int(pin)

    @method()
    def RequestPinCode(self, device: "o") -> "s":
        return str(self.pins.get(device, ""))

    @method()
    def RequestConfirmation(self, device: "o", passkey: "u"):
        return

    @method()
    def RequestAuthorization(self, device: "o"):
        return

    @method()
    def AuthorizeService(self, device: "o", uuid: "s"):
        return

    @method()
    def DisplayPasskey(self, device: "o", passkey: "u", entered: "q"):
        return

    @method()
    def Cancel(self):
        return

    @method()
    def Release(self):
        return


class Bluez:
    def __init__(self, heaters):
        self.heaters = heaters

    async def __aenter__(self):
        self.bus = await MessageBus(bus_type=BusType.SYSTEM).connect()
        pins = {dev_path(h["address"]): h["pin"] for h in self.heaters if h.get("pin")}
        self.bus.export(AGENT_PATH, PinAgent(pins))
        am = await self._iface("/org/bluez", "org.bluez.AgentManager1")
        await am.call_register_agent(AGENT_PATH, "KeyboardOnly")
        try:
            await am.call_request_default_agent(AGENT_PATH)
        except Exception:
            pass
        return self

    async def __aexit__(self, *a):
        try:
            am = await self._iface("/org/bluez", "org.bluez.AgentManager1")
            await am.call_unregister_agent(AGENT_PATH)
        except Exception:
            pass
        self.bus.disconnect()

    async def _iface(self, path, name):
        intro = await self.bus.introspect(BLUEZ, path)
        return self.bus.get_proxy_object(BLUEZ, path, intro).get_interface(name)

    async def objects(self):
        om = await self._iface("/", "org.freedesktop.DBus.ObjectManager")
        return await om.call_get_managed_objects()

    async def discover_until(self, paths, timeout=30):
        """Scan until every path in `paths` is known to BlueZ (or timeout)."""
        ad = await self._iface(ADAPTER, "org.bluez.Adapter1")
        try:
            await ad.call_start_discovery()
        except Exception:
            pass
        end = time.time() + timeout
        try:
            while time.time() < end:
                objs = await self.objects()
                if all(p in objs for p in paths):
                    return True
                await asyncio.sleep(1)
            return False
        finally:
            try:
                await ad.call_stop_discovery()
            except Exception:
                pass

    async def ensure_connected(self, h, tries=3):
        p = dev_path(h["address"])
        objs = await self.objects()
        if p not in objs:
            if not await self.discover_until([p]):
                raise RuntimeError(f"{h['label']}: not found (out of range, or another phone is connected)")
            objs = await self.objects()
        dev = await self._iface(p, "org.bluez.Device1")
        props = await self._iface(p, "org.freedesktop.DBus.Properties")
        if not await dev.get_paired():
            last = None
            for _ in range(tries):
                try:
                    await dev.call_pair()
                    break
                except Exception as e:  # first attempt often fails
                    last = e
                    await asyncio.sleep(2)
            else:
                raise RuntimeError(f"{h['label']}: pairing failed: {last}")
        await props.call_set("org.bluez.Device1", "Trusted", Variant("b", True))
        await props.call_set("org.bluez.Device1", "Alias", Variant("s", h["label"]))
        last = None
        for _ in range(tries):
            if await dev.get_connected():
                break
            try:
                await dev.call_connect()
                break
            except Exception as e:
                last = e
                await asyncio.sleep(2)
        else:
            raise RuntimeError(f"{h['label']}: connect failed: {last}")
        for _ in range(20):  # wait for GATT discovery
            if await dev.get_services_resolved():
                break
            await asyncio.sleep(0.5)
        return Heater(self, h, p, dev)


class Heater:
    def __init__(self, bz, h, path, dev):
        self.bz, self.h, self.path, self.dev = bz, h, path, dev
        self._chars = None

    async def chars(self):
        if self._chars is None:
            self._chars = {}
            for p, ifs in (await self.bz.objects()).items():
                c = ifs.get("org.bluez.GattCharacteristic1")
                if c and p.startswith(self.path + "/"):
                    self._chars.setdefault(c["UUID"].value, p)
        return self._chars

    async def _c(self, uuid):
        p = (await self.chars()).get(uuid)
        if not p:  # GATT discovery can lag behind ServicesResolved; refresh once
            await asyncio.sleep(3); self._chars = None
            p = (await self.chars()).get(uuid)
        if not p:
            raise RuntimeError(f"{self.h['label']}: characteristic {uuid[4:8]} missing")
        return await self.bz._iface(p, "org.bluez.GattCharacteristic1")

    async def read(self, uuid):
        b = bytes(await (await self._c(uuid)).call_read_value({}))
        if not b:  # BlueZ occasionally returns empty right after pairing: reconnect once
            await self.dev.call_disconnect(); await asyncio.sleep(2)
            await self.dev.call_connect(); await asyncio.sleep(2)
            self._chars = None
            b = bytes(await (await self._c(uuid)).call_read_value({}))
        return b

    async def write(self, uuid, data):
        await (await self._c(uuid)).call_write_value(bytes(data), {"type": Variant("s", "request")})

    async def status(self):
        mode = await self.read(C_MODE)
        target = await self.read(C_TARGET)
        await self.write(C_ROOM, [1, 0, 0]); room = await self.read(C_ROOM)
        heating, demand = False, 0  # elements numbered from 1; reply [n, active, demand%]
        for n in (1, 2):
            await self.write(C_TRAC, [n, 0, 0]); r = await self.read(C_TRAC)
            if len(r) >= 3 and r[0] == n and r[1]:
                heating, demand = True, max(demand, r[2])
        off = False
        if mode[0] == 1:  # timer: is it running our off schedule?
            await self.write(C_SCHEDULE, [0, 3, 0]); sch = await self.read(C_SCHEDULE)
            off = list(sch[3:27]) == OFF_SLOTS
        p = await self.read(C_POWER)
        watts = (p[0] | p[1] << 8) * demand // 100 if len(p) >= 2 else None  # estimate, not metered
        err = await self.read(C_ERROR)
        model = (await self.read(C_MODEL)).split(b"\0")[0].decode(errors="replace")
        rssi = None
        try:
            rssi = await self.dev.get_rssi()
        except Exception:
            pass
        return {
            "label": self.h["label"], "model": model,
            "mode": "off" if off else MODES.get(mode[0], str(mode[0])), "target": target[0],
            "room": room[1] + room[2] / 10 if len(room) >= 3 else None,
            "heating": heating, "watts": watts,
            "error": err[0] if err else None, "rssi": rssi, "ts": int(time.time()),
        }

    async def set_target(self, t):
        lo, hi = 7, 30
        try:
            r = await self.read(C_RANGE); lo, hi = r[0], r[2]
        except Exception:
            pass
        if not lo <= t <= hi:
            raise RuntimeError(f"target must be {lo}-{hi} °C")
        if (await self.read(C_MODE))[0] not in (2, 3):  # frost/timer/off: leave it first
            await self.write(C_MODE, [3, 0, 0, 0, 0, 0])
        await self.write(C_TARGET, [t, 0])

    async def set_mode(self, m):
        if m == "off":
            return await self.off()
        await self.write(C_MODE, [MODE_IDS[m], 0, 0, 0, 0, 0])

    async def off(self):
        """True off: User Timer whose only slots are four 1-minute 7 °C slots at 03:00-03:04
        (empty slots are rejected). Between slots the target is 0 °C. Overwrites the timer schedule."""
        for day in range(7):
            await self.write(C_SCHEDULE, [1, 3, day] + OFF_SLOTS + [0, 0, 0, 0])
            await asyncio.sleep(0.3)
        await self.write(C_MODE, [1, 3, 0, 0, 0, 0])

    async def clock(self):
        c = await self.read(C_CLOCK)
        return c, f"20{c[1]:02d}-{c[2]:02d}-{c[3]:02d} {c[4]:02d}:{c[5]:02d}"

    async def sync_clock(self):
        """Write local time; keep the heater's own last (DST) flag byte."""
        old, _ = await self.clock()
        t = time.localtime()
        await self.write(C_CLOCK, [20, t.tm_year % 100, t.tm_mon, t.tm_mday,
                                   t.tm_hour, t.tm_min, old[6] if len(old) > 6 else 0])

    async def disconnect(self):
        try:
            await self.dev.call_disconnect()
        except Exception:
            pass


# ---------- history ----------
def db():
    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    c = sqlite3.connect(DB_PATH)
    c.execute("create table if not exists readings(ts int, label text, mode text, target int,"
              " room real, heating int, error int, rssi int)")
    c.execute("create table if not exists rssi(ts int, label text, rssi int)")
    return c


def save(s):
    with db() as c:
        c.execute("insert into readings values(?,?,?,?,?,?,?,?)",
                  (s["ts"], s["label"], s["mode"], s["target"], s["room"],
                   s["heating"], s["error"], s["rssi"]))


def show(s):
    room = f"{s['room']:.1f}°C" if s["room"] is not None else "?"
    heat = {True: "heating", False: "idle", None: "?"}[s["heating"]]
    print(f"{s['label']:<12} {s['mode']:<7} target {s['target']:>2}°C  room {room:<7} {heat:<8}"
          f"{'  ~%dW' % s['watts'] if s.get('watts') else ''}  rssi {s['rssi']}  {s['model']}" + (f"  ERROR {s['error']}" if s["error"] else ""))


# ---------- commands ----------
async def with_heater(bz, h, fn, keep=False):
    try:
        hz = await bz.ensure_connected(h)
    except Exception as e:
        print(f"{h['label']:<12} {e}")
        return
    try:
        return await fn(hz)
    except Exception as e:
        print(f"{h['label']:<12} error: {e}")
    finally:
        if not keep:
            await hz.disconnect()


async def cmd_status(bz, hs, a):
    for h in hs:
        async def f(hz):
            s = await hz.status(); save(s); show(s)
        await with_heater(bz, h, f, a.keep)


async def cmd_set(bz, hs, a):
    for h in hs:
        async def f(hz):
            await hz.set_target(a.temp); await asyncio.sleep(1)
            s = await hz.status(); save(s); show(s)
        await with_heater(bz, h, f, a.keep)


async def cmd_mode(bz, hs, a):
    for h in hs:
        async def f(hz):
            await hz.set_mode(a.mode); await asyncio.sleep(1)
            s = await hz.status(); save(s); show(s)
        await with_heater(bz, h, f, a.keep)


async def cmd_pair(bz, hs, a):
    for h in hs:
        async def f(hz):
            print(f"{h['label']:<12} paired and connected")
        await with_heater(bz, h, f, a.keep)


async def cmd_clock(bz, hs, a):
    for h in hs:
        async def f(hz):
            _, before = await hz.clock()
            if a.sync:
                await hz.sync_clock(); await asyncio.sleep(1)
                _, after = await hz.clock()
                print(f"{h['label']:<12} clock {before} -> {after}")
            else:
                print(f"{h['label']:<12} clock {before}   (laptop {time.strftime('%Y-%m-%d %H:%M')})")
        await with_heater(bz, h, f, a.keep)


async def cmd_watch(bz, hs, a):
    """Poll every heater every --interval s; between polls record advert RSSI (presence)."""
    if a.sim:
        while True:
            await cmd_status(bz, hs, a)
            with db() as c:
                for h in hs:
                    c.execute("insert into rssi values(?,?,?)",
                              (int(time.time()), h["label"], bz.models[h["address"]].rssi))
            await bz.__aexit__()  # persist sim state each round
            await asyncio.sleep(a.interval)
    paths = {dev_path(h["address"]): h["label"] for h in hs}

    def on_props(path):
        def cb(iface, changed, invalid):
            if "RSSI" in changed:
                with db() as c:
                    c.execute("insert into rssi values(?,?,?)",
                              (int(time.time()), paths[path], changed["RSSI"].value))
        return cb

    for p in paths:
        try:
            (await bz._iface(p, "org.freedesktop.DBus.Properties")).on_properties_changed(on_props(p))
        except Exception:
            pass  # not known yet; picked up after first discovery
    ad = await bz._iface(ADAPTER, "org.bluez.Adapter1")
    await ad.call_set_discovery_filter({"Transport": Variant("s", "le"),
                                        "DuplicateData": Variant("b", True)})
    while True:
        await cmd_status(bz, hs, a)
        try:
            await ad.call_start_discovery()
        except Exception:
            pass
        await asyncio.sleep(a.interval)
        try:
            await ad.call_stop_discovery()
        except Exception:
            pass


def cmd_history(hs, a):
    since = int(time.time()) - int(a.hours * 3600)
    with db() as c:
        for h in hs:
            print(f"== {h['label']}")
            for r in c.execute("select ts,mode,target,room,heating,rssi from readings "
                               "where label=? and ts>=? order by ts", (h["label"], since)):
                t = time.strftime("%m-%d %H:%M", time.localtime(r[0]))
                print(f"  {t}  {r[1]:<7} target {r[2]}  room {r[3]}  heating {r[4]}  rssi {r[5]}")
            n = c.execute("select count(*), avg(rssi) from rssi where label=? and ts>=?",
                          (h["label"], since)).fetchone()
            if n[0]:
                print(f"  advert RSSI samples {n[0]}, mean {n[1]:.0f} dBm")


def cmd_near(hs, a):
    """Which heater hears us loudest over the last minute: a rough 'which room am I in'."""
    since = int(time.time()) - 60
    with db() as c:
        rows = c.execute("select label, avg(rssi) from rssi where ts>=? group by label "
                         "order by avg(rssi) desc", (since,)).fetchall()
    if not rows:
        print("No recent RSSI (run `watch` first)")
    for l, r in rows:
        print(f"{l:<12} {r:.0f} dBm")


def main():
    ap = argparse.ArgumentParser(prog="dimplex", description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--config")
    ap.add_argument("--keep", action="store_true", help="stay connected after the command")
    ap.add_argument("--sim", action="store_true", default=bool(os.environ.get("DIMPLEX_SIM")),
                    help="simulate the configured heaters in-process (no Bluetooth); or DIMPLEX_SIM=1")
    sp = ap.add_subparsers(dest="cmd", required=True)
    for n in ("status", "pair", "on"):
        p = sp.add_parser(n); p.add_argument("heater", nargs="?", default="all")
    p = sp.add_parser("set", help="set target °C"); p.add_argument("heater"); p.add_argument("temp", type=int)
    p = sp.add_parser("mode"); p.add_argument("heater"); p.add_argument("mode", choices=list(MODE_IDS))
    p = sp.add_parser("off", help="true off (timer with no real slots); overwrites the timer schedule"); p.add_argument("heater")
    p = sp.add_parser("clock", help="show heater clock; --sync sets it to laptop time")
    p.add_argument("heater", nargs="?", default="all"); p.add_argument("--sync", action="store_true")
    p = sp.add_parser("watch", help="log status + RSSI forever"); p.add_argument("heater", nargs="?", default="all")
    p.add_argument("--interval", type=int, default=300)
    p = sp.add_parser("history"); p.add_argument("heater", nargs="?", default="all")
    p.add_argument("--hours", type=float, default=24)
    p = sp.add_parser("near", help="strongest heater signal in the last minute")
    a = ap.parse_args()

    heaters = load_config(a.config)
    if a.cmd == "history":
        return cmd_history(pick(heaters, a.heater), a)
    if a.cmd == "near":
        return cmd_near(heaters, a)
    if a.cmd == "off":
        a.cmd, a.mode = "mode", "off"
    if a.cmd == "on":
        a.cmd, a.mode = "mode", "eco"
    hs = pick(heaters, a.heater)

    async def run():
        backend = Bluez
        if a.sim:
            from simbackend import SimBluez as backend
        async with backend(heaters) as bz:
            await {"status": cmd_status, "set": cmd_set, "mode": cmd_mode,
                   "pair": cmd_pair, "watch": cmd_watch, "clock": cmd_clock}[a.cmd](bz, hs, a)
    try:
        asyncio.run(run())
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
