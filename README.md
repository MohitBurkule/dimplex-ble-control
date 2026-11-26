# dimplex-ble-control

Local Bluetooth control for Dimplex PLX panel heaters (Glen Dimplex BLE protocol): a Linux CLI and an Android app.
Unofficial; not affiliated with Dimplex. Protocol credit: [dimplex-qrad-smart-bridge](https://github.com/philcluff/dimplex-qrad-smart-bridge) and [Sunhouse project](https://github.com/JYewman/Sunhouse-Dimplex-Storage-Heater-ESPHome-Home-Assistant).

## Config
Copy `heaters.example.json` to `heaters.json` (gitignored) with each heater's label, Bluetooth address and the 6-digit PIN shown on its display. Pairing then happens automatically.

## CLI (Linux, BlueZ)
```
uv run --with dbus-fast cli/dimplex.py status            # all heaters
uv run --with dbus-fast cli/dimplex.py set Kitchen 21
uv run --with dbus-fast cli/dimplex.py off Bedroom       # true off: timer with only 1-min 7 °C slots at 03:00 (replaces timer schedule)
uv run --with dbus-fast cli/dimplex.py clock --sync
uv run --with dbus-fast cli/dimplex.py watch --interval 300   # log history + RSSI
uv run --with dbus-fast cli/dimplex.py history Kitchen --hours 48
uv run --with dbus-fast cli/dimplex.py near             # strongest heater signal = which room you're in
```
Heaters accept one connection at a time; the CLI disconnects after each command (`--keep` to stay).

## Android
`android/` — built by GitHub Actions; download the APK from the workflow artifacts or Releases. See `docs/tasker.md` and `docs/roadmap.md`.

## Heater simulator (test without hardware)
`sim/heater_sim.py` turns a Linux laptop into a fake heater over BLE, speaking the same protocol
(modes, Eco/Manual by setpoint, timer schedule + "off" schedule, element demand, room temperature
model, drifting clock) and reproducing real-hardware quirks: long writes/reads beyond the MTU fail
(status 133 on Android until MTU is negotiated), one connection at a time, passkey pairing required,
no standby mode, empty schedule slots rejected. `--no-quirks` for an ideal heater.
```
uv run --with dbus-fast sim/heater_sim.py --name SIM001 --room 16 --speed 20
```
The pairing PIN is printed in the terminal. Run it on a different machine from the one running the CLI.
Model tests: `cd sim && uv run --with dbus-fast --with pytest python -m pytest -q`.
