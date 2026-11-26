#!/usr/bin/env python3
"""Simulated Dimplex PLX heater: turns a Linux laptop (BlueZ) into a BLE peripheral that speaks the
same GATT protocol as the real heaters, so the Android app / CLI can be tested without hardware.

  sudo-less run (needs a BlueZ adapter that supports advertising):
    uv run --with dbus-fast sim/heater_sim.py --name SIM001 --room 18

Pairing: characteristics require an authenticated (passkey) link like the real heater; the
passkey BlueZ generates is printed here, just as a real heater shows it on its display.
Behaviour (all confirmed on real PLX200E/PLX125E units, see README protocol notes):
- 0x1001 mode [mode, sub, ...]: 1 Timer, 2 Manual, 3 Eco, 4 Frost; anything else ignored.
  Manual/Eco are one family: reads 3 when setpoint <= 19, 2 when >= 20.
- 0x1023 target LE16; in Timer it is the current slot's temp, or 0 outside slots (= off).
- 0x1002 schedule: write [0,3,day] selects, read returns 31 bytes; write [1,3,day,+24 slot bytes,+4]
  stores it (rejected if any slot is all zeros, like the real heater).
- 0x0002 selector [n,0,0] -> [n, active, demand%] for elements 1..2 (0 and 3 read 000000).
- 0x0008 selector [1,0,0] -> [1, whole, tenths] room temperature.
- 0x0006 clock [20,YY,MM,DD,hh,mm,flag], writable. Day 0 of the schedule = Monday (assumption).
- 0x000a rated power LE16 (2000 W).

Real-hardware quirks reproduced (disable with --no-quirks):
- Packet length: writes longer than MTU-3 (i.e. needing a prepared/"long" write) fail, and long
  reads (offset > 0) fail. With Android's default MTU 23 the 31-byte schedule write fails with
  GATT status 133 until the app negotiates a bigger MTU, exactly as with the real heaters.
- One connection at a time: advertising stops while a central is connected.
- Unpaired access is refused (authenticated encryption required), triggering passkey pairing.
- No standby: mode 6 (and other unknown modes) are accepted but ignored.
- Empty schedule slots are rejected silently (write succeeds, schedule unchanged).
- Element selector 0 (and 3) read 000000; only 1 and 2 report heating.
- Clock starts 1-4 h slow; heater picks Eco/Manual from the setpoint; Timer target 0 between slots.
"""
import argparse, asyncio, datetime as dt, random, time

from dbus_fast import BusType, Variant
from dbus_fast.aio import MessageBus
from dbus_fast.service import PropertyAccess, ServiceInterface, dbus_property, method

BLUEZ = "org.bluez"
ADAPTER = "/org/bluez/hci0"
APP = "/com/mohit/dimplexsim"
SECURE = ["encrypt-authenticated-read", "encrypt-authenticated-write"]


def cu(x):
    return f"0000{x:04x}-0000-1000-8000-00805f9b34fb"


def svc_uuid(n):
    return f"00000000-000{n}-1000-8000-00805f9b34fb"


class Heater:
    """Pure state machine; no Bluetooth here so it can be unit-tested."""

    def __init__(self, model="PLX200E;C;", watts=2000, room=18.0, ambient=10.0):
        self.model, self.watts, self.room, self.ambient = model, watts, room, ambient
        self.mode, self.sub, self.setpoint = 3, 0, 18
        self.clock_offset = random.randint(-4 * 3600, -3600)  # real heaters' clocks drift badly
        self.preemptive, self.open_window = 0, bytearray(b"\x01" + b"\x00" * 8)
        default = [6, 30, 9, 30, 21, 0, 11, 0, 13, 0, 21, 0, 15, 0, 17, 0, 21, 0, 18, 0, 22, 0, 21, 0]
        self.schedule = {d: list(default) for d in range(7)}
        self.sched_sel, self.trac_sel, self.temp_sel, self.ver_sel = 0, 0, 0, 0
        self.elements_on = False

    # ---- time / target ----
    def now(self):
        return dt.datetime.now() + dt.timedelta(seconds=self.clock_offset)

    def slot_temp(self):
        n = self.now(); m = n.hour * 60 + n.minute
        s = self.schedule[n.weekday()]
        for i in range(4):
            sh, sm, eh, em, t, _ = s[i * 6:i * 6 + 6]
            if sh * 60 + sm <= m < eh * 60 + em:
                return t
        return 0

    def target(self):
        if self.mode == 1:
            return self.slot_temp()
        if self.mode == 4:
            return 7
        return self.setpoint

    def tick(self, dt_s):
        self.elements_on = self.room < self.target() - 0.2
        heat = 0.004 * self.watts / 1000 if self.elements_on else 0.0   # °C/s at full power
        self.room += dt_s * (heat - (self.room - self.ambient) * 0.0004)

    # ---- GATT reads ----
    def read(self, x):
        if x == 0x1001:
            m = self.mode
            if m in (2, 3):
                m = 3 if self.setpoint <= 19 else 2
            return [m, self.sub if m == 1 else 0, 0, 0, 0, 0]
        if x in (0x1023, 0x1003):
            return [self.target(), 0]
        if x == 0x1006: return [7, 0, 30, 0]
        if x == 0x1002:
            return [0, 3, self.sched_sel] + self.schedule[self.sched_sel] + [0, 0, 0, 0]
        if x == 0x0002:
            n = self.trac_sel
            on = n in (1, 2) and self.elements_on
            return [n, 1 if on else 0, 100 if on else 0] if n in (1, 2) else [n, 0, 0]
        if x == 0x0008:
            r = round(self.room, 1)
            return [self.temp_sel, int(r), int(round((r - int(r)) * 10))] if self.temp_sel == 1 else [self.temp_sel, 0, 0]
        if x == 0x0006:
            n = self.now(); return [20, n.year % 100, n.month, n.day, n.hour, n.minute, 1]
        if x == 0x000a: return [self.watts & 0xff, self.watts >> 8, 0x98, 0x08, 0, 0, 0, 0]
        if x == 0x1008: return [self.preemptive]
        if x == 0x1007: return list(self.open_window)
        if x == 0x1011: return [0]
        if x == 0x2005: return list(self.model.encode().ljust(32, b"\0"))
        if x == 0x2004: return list(b"<Dimplex>")
        if x == 0x2001: return [self.ver_sel, 1, 18]
        return [0]

    # ---- GATT writes ----
    def write(self, x, v):
        v = list(v)
        if x == 0x1001 and v and v[0] in (1, 2, 3, 4):
            self.mode, self.sub = v[0], v[1] if len(v) > 1 else 0
            if v[0] == 2: self.setpoint = 21   # presets, as on the real heater
            if v[0] == 3: self.setpoint = 18
        elif x == 0x1023 and v and self.mode in (2, 3) and 7 <= v[0] <= 30:
            self.setpoint = v[0]
        elif x == 0x1002 and len(v) >= 3:
            day = v[2] % 7
            if v[0] == 0:
                self.sched_sel = day
            elif v[0] == 1 and len(v) >= 27:
                slots = v[3:27]
                if all(any(slots[i * 6:i * 6 + 5]) for i in range(4)):  # empty slots are rejected
                    self.schedule[day] = slots
        elif x == 0x0002 and v: self.trac_sel = v[0]
        elif x == 0x0008 and v: self.temp_sel = v[0]
        elif x == 0x2001 and v: self.ver_sel = v[0]
        elif x == 0x0006 and len(v) >= 6:
            try:
                want = dt.datetime(2000 + v[1], v[2], v[3], v[4], v[5])
                self.clock_offset = (want - dt.datetime.now().replace(second=0, microsecond=0)).total_seconds()
            except ValueError:
                pass
        elif x == 0x1008 and v: self.preemptive = v[0]
        elif x == 0x1007 and v: self.open_window[: len(v)] = bytes(v[:9])


# Real layout: service index -> characteristic ids (so the app sees the same shape as hardware)
LAYOUT = {
    0: [0x0006, 0x1001, 0x1002, 0x1003, 0x1005, 0x1006, 0x1007, 0x1008, 0x100d, 0x1011, 0x1016,
        0x1017, 0x1023, 0x1027, 0x1028, 0x102a],
    1: [0x0001, 0x0002, 0x0007, 0x0008, 0x100e, 0x1010, 0x1020],
    2: [0x0005, 0x100a, 0x100b, 0x100c, 0x100f, 0x1019],
    3: [0x000a, 0x000b, 0x000c, 0x1022, 0x1029, 0x1050, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005,
        0x2009, 0x200a, 0x200b, 0x200c, 0x200d],
}
READ_ONLY = {0x1011, 0x2002, 0x2004, 0x2005, 0x2009, 0x200a, 0x200b, 0x200c, 0x200d, 0x1029, 0x1050, 0x0007}


class Service(ServiceInterface):
    def __init__(self, uuid):
        super().__init__("org.bluez.GattService1"); self._uuid = uuid

    @dbus_property(access=PropertyAccess.READ)
    def UUID(self) -> "s": return self._uuid

    @dbus_property(access=PropertyAccess.READ)
    def Primary(self) -> "b": return True


class GattError(Exception):
    pass


GattError.__name__ = "org.bluez.Error.Failed"   # dbus-fast uses the class name as error name


class Characteristic(ServiceInterface):
    quirks = True
    on_access = None  # callback(device_path): used to track the connected central

    def __init__(self, heater, cid, service_path, log):
        super().__init__("org.bluez.GattCharacteristic1")
        self.h, self.cid, self.svc, self.log = heater, cid, service_path, log
        self.flags = ["read"] + ([] if cid in READ_ONLY else ["write"]) + SECURE

    @dbus_property(access=PropertyAccess.READ)
    def UUID(self) -> "s": return cu(self.cid)

    @dbus_property(access=PropertyAccess.READ)
    def Service(self) -> "o": return self.svc

    @dbus_property(access=PropertyAccess.READ)
    def Flags(self) -> "as": return self.flags

    def _seen(self, options):
        if Characteristic.on_access and "device" in options:
            Characteristic.on_access(options["device"].value)

    @method()
    def ReadValue(self, options: "a{sv}") -> "ay":
        self._seen(options)
        v = bytes(self.h.read(self.cid))
        off = options["offset"].value if "offset" in options else 0
        mtu = options["mtu"].value if "mtu" in options else 23
        if self.quirks and off > 0:
            # Real heaters don't serve Read Blob: a read longer than MTU-1 comes back truncated
            print(f"  read  {self.cid:04x} offset {off} REFUSED (long read, mtu {mtu})")
            raise GattError("long read not supported")
        if self.log: print(f"  read  {self.cid:04x} -> {v.hex()}  (mtu {mtu})")
        return v

    @method()
    def WriteValue(self, value: "ay", options: "a{sv}"):
        self._seen(options)
        v = bytes(value)
        mtu = options["mtu"].value if "mtu" in options else 23
        prepared = "prepare-authorize" in options or ("offset" in options and options["offset"].value > 0)
        if self.quirks and (len(v) > mtu - 3 or prepared):
            print(f"  write {self.cid:04x} {len(v)} bytes REFUSED (needs long write, mtu {mtu}) -> app sees status 133")
            raise GattError("long write not supported")
        if self.log: print(f"  write {self.cid:04x} <- {v.hex()}  (mtu {mtu})")
        self.h.write(self.cid, v)


class ObjectManager(ServiceInterface):
    def __init__(self):
        super().__init__("org.freedesktop.DBus.ObjectManager"); self.objects = {}

    @method()
    def GetManagedObjects(self) -> "a{oa{sa{sv}}}":
        return self.objects


class Advertisement(ServiceInterface):
    def __init__(self, name):
        super().__init__("org.bluez.LEAdvertisement1"); self.name = name

    @dbus_property(access=PropertyAccess.READ)
    def Type(self) -> "s": return "peripheral"

    @dbus_property(access=PropertyAccess.READ)
    def LocalName(self) -> "s": return self.name

    @dbus_property(access=PropertyAccess.READ)
    def Includes(self) -> "as": return []

    @method()
    def Release(self): pass


class DisplayAgent(ServiceInterface):
    """Shows the pairing passkey, like the heater's display."""

    def __init__(self):
        super().__init__("org.bluez.Agent1")

    @method()
    def DisplayPasskey(self, device: "o", passkey: "u", entered: "q"):
        print(f"\n>>> PAIRING PIN: {passkey:06d}  (enter this in the app)\n", flush=True)

    @method()
    def RequestConfirmation(self, device: "o", passkey: "u"):
        print(f"\n>>> PAIRING PIN: {passkey:06d}\n", flush=True)

    @method()
    def RequestAuthorization(self, device: "o"): pass

    @method()
    def AuthorizeService(self, device: "o", uuid: "s"): pass

    @method()
    def Cancel(self): pass

    @method()
    def Release(self): pass


async def iface(bus, path, name):
    return bus.get_proxy_object(BLUEZ, path, await bus.introspect(BLUEZ, path)).get_interface(name)


async def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--name", default="SIM001", help="advertised prefix; '<Dimplex>' is appended")
    ap.add_argument("--model", default="PLX200E;C;")
    ap.add_argument("--watts", type=int, default=2000)
    ap.add_argument("--room", type=float, default=18.0)
    ap.add_argument("--speed", type=float, default=1.0, help="room-temperature time multiplier")
    ap.add_argument("--quiet", action="store_true", help="don't log reads/writes")
    ap.add_argument("--no-quirks", action="store_true", help="behave like an ideal heater")
    a = ap.parse_args()

    h = Heater(a.model, a.watts, a.room)
    Characteristic.quirks = not a.no_quirks
    bus = await MessageBus(bus_type=BusType.SYSTEM).connect()
    om = ObjectManager()
    bus.export(APP, om)
    for n, chars in LAYOUT.items():
        sp = f"{APP}/service{n}"
        s = Service(svc_uuid(n)); bus.export(sp, s)
        om.objects[sp] = {"org.bluez.GattService1": {"UUID": Variant("s", svc_uuid(n)), "Primary": Variant("b", True)}}
        for i, cid in enumerate(chars):
            cp = f"{sp}/char{i}"
            c = Characteristic(h, cid, sp, not a.quiet); bus.export(cp, c)
            om.objects[cp] = {"org.bluez.GattCharacteristic1": {
                "UUID": Variant("s", cu(cid)), "Service": Variant("o", sp), "Flags": Variant("as", c.flags)}}

    bus.export(f"{APP}/agent", DisplayAgent())
    am = await iface(bus, "/org/bluez", "org.bluez.AgentManager1")
    await am.call_register_agent(f"{APP}/agent", "DisplayOnly")
    await am.call_request_default_agent(f"{APP}/agent")

    props = await iface(bus, ADAPTER, "org.freedesktop.DBus.Properties")
    for k, v in (("Powered", True), ("Pairable", True), ("Discoverable", True)):
        try:
            await props.call_set("org.bluez.Adapter1", k, Variant("b", v))
        except Exception as e:
            print(f"warning: could not set {k}: {e}")

    await (await iface(bus, ADAPTER, "org.bluez.GattManager1")).call_register_application(APP, {})
    name = f"{a.name}<Dimplex>"
    bus.export(f"{APP}/adv", Advertisement(name))
    await (await iface(bus, ADAPTER, "org.bluez.LEAdvertisingManager1")).call_register_advertisement(f"{APP}/adv", {})
    print(f"Advertising as {name}  model {a.model}  quirks {'on' if not a.no_quirks else 'off'}  (Ctrl-C to stop)", flush=True)

    adv_mgr = await iface(bus, ADAPTER, "org.bluez.LEAdvertisingManager1")
    central = {"path": None, "advertising": True}
    Characteristic.on_access = lambda p: central.__setitem__("path", p)

    async def track_connection():
        """One connection at a time: stop advertising while the central is connected."""
        if a.no_quirks or not central["path"]:
            return
        try:
            dp = await iface(bus, central["path"], "org.freedesktop.DBus.Properties")
            connected = (await dp.call_get("org.bluez.Device1", "Connected")).value
        except Exception:
            connected = False
        if connected and central["advertising"]:
            await adv_mgr.call_unregister_advertisement(f"{APP}/adv"); central["advertising"] = False
            print("central connected: advertising stopped", flush=True)
        elif not connected and not central["advertising"]:
            await adv_mgr.call_register_advertisement(f"{APP}/adv", {}); central["advertising"] = True
            central["path"] = None
            print("central disconnected: advertising again", flush=True)

    last = time.time(); shown = None
    while True:
        await asyncio.sleep(2)
        await track_connection()
        t = time.time(); h.tick((t - last) * a.speed); last = t
        s = (h.read(0x1001)[0], h.target(), round(h.room, 1), h.elements_on)
        if s != shown:
            mode = {1: "timer", 2: "manual", 3: "eco", 4: "frost"}[s[0]]
            if s[0] == 1 and s[1] == 0: mode += " (off: outside slots)"
            print(f"[{h.now():%H:%M}] mode {mode}  target {s[1]}°C  room {s[2]:.1f}°C  {'HEATING' if s[3] else 'idle'}", flush=True)
            shown = s


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
