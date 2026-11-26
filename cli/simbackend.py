"""In-process simulated heaters for the CLI (`--sim` or DIMPLEX_SIM=1): no Bluetooth needed.

Uses the same Heater model as sim/heater_sim.py, one per configured heater, with state kept in
~/.local/share/dimplex/sim_state.pkl so changes persist between CLI runs (time keeps flowing:
the room model is advanced by the real time elapsed since the last run).
"""
import pickle, random, sys, time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "sim"))
from heater_sim import Heater as Model  # noqa: E402

STATE = Path.home() / ".local/share/dimplex/sim_state.pkl"
MODELS = ["PLX200E;C;", "PLX200E;C;", "PLX125E;B;", "PLX125E;B;"]


class _Dev:
    """Stand-in for the BlueZ Device1 proxy."""
    def __init__(self, sim):
        self.sim = sim

    async def get_rssi(self):
        return self.sim.rssi

    async def call_disconnect(self):
        pass

    async def call_connect(self):
        pass


class SimBluez:
    def __init__(self, heaters):
        self.heaters = heaters

    async def __aenter__(self):
        self.models = {}
        if STATE.exists():
            try:
                self.models, saved_at = pickle.loads(STATE.read_bytes())
                for m in self.models.values():
                    m.tick(time.time() - saved_at)
            except Exception:
                self.models = {}
        for i, h in enumerate(self.heaters):
            if h["address"] not in self.models:
                watts = 2000 if MODELS[i % 4].startswith("PLX200") else 1250
                m = Model(MODELS[i % 4], watts, room=random.uniform(15, 21))
                m.rssi = random.randint(-90, -60)
                self.models[h["address"]] = m
        return self

    async def __aexit__(self, *a):
        STATE.parent.mkdir(parents=True, exist_ok=True)
        STATE.write_bytes(pickle.dumps((self.models, time.time())))

    async def ensure_connected(self, h, tries=3):
        from dimplex import Heater
        m = self.models[h["address"]]
        m.rssi = max(-100, min(-45, m.rssi + random.randint(-4, 4)))  # you walking around
        hz = Heater(self, h, None, _Dev(m))
        hz.read = lambda uuid: _read(m, uuid)
        hz.write = lambda uuid, data: _write(m, uuid, data)
        return hz


async def _read(m, uuid):
    return bytes(m.read(int(uuid[4:8], 16)))


async def _write(m, uuid, data):
    m.write(int(uuid[4:8], 16), list(data))
