"""Model tests for the simulator (no Bluetooth): uv run --with dbus-fast python -m pytest sim -q"""
from heater_sim import Heater

OFF = [b for i in range(4) for b in (3, i, 3, i + 1, 7, 0)]


def test_manual_eco_by_setpoint():
    h = Heater(); h.write(0x1001, [2, 0, 0, 0, 0, 0])
    assert h.read(0x1001)[0] == 2 and h.read(0x1023) == [21, 0]
    h.write(0x1023, [18, 0]); assert h.read(0x1001)[0] == 3


def test_no_standby():
    h = Heater(); before = h.read(0x1001); h.write(0x1001, [6, 3, 0, 0, 0, 0]); assert h.read(0x1001) == before


def test_off_schedule_and_empty_slots_rejected():
    h = Heater()
    h.write(0x1002, [1, 3, 0] + [3, 0, 3, 1, 7, 0] + [0] * 18 + [0] * 4)  # empty slots: ignored
    h.write(0x1002, [0, 3, 0]); assert h.read(0x1002)[3:27] != [3, 0, 3, 1, 7, 0] + [0] * 18
    for d in range(7): h.write(0x1002, [1, 3, d] + OFF + [0] * 4)
    h.write(0x1001, [1, 3, 0, 0, 0, 0])
    h.write(0x1002, [0, 3, 0]); assert h.read(0x1002)[3:27] == OFF and len(h.read(0x1002)) == 31
    if not (h.now().hour == 3 and h.now().minute < 4):
        assert h.read(0x1023) == [0, 0]


def test_elements_and_room():
    h = Heater(room=15); h.write(0x1001, [2, 0, 0, 0, 0, 0]); h.tick(1)
    h.write(0x0002, [0, 0, 0]); assert h.read(0x0002) == [0, 0, 0]
    h.write(0x0002, [1, 0, 0]); assert h.read(0x0002) == [1, 1, 100]
    h.write(0x0008, [1, 0, 0]); assert h.read(0x0008)[0] == 1
    t0 = h.room; h.tick(600); assert h.room > t0
