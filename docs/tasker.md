# Tasker integration

The app exposes a broadcast API (receiver `com.mohit.dimplex.tasker.TaskerReceiver`).
Set a **shared secret** in the app's Settings first; with an empty secret every command is rejected.

## Commands (Tasker: *Send Intent*, Target = Broadcast Receiver, Package = `com.mohit.dimplex`)

| Action | Extras |
|---|---|
| `com.mohit.dimplex.SET_TEMP` | `secret`, `label`, `temp` (7–30, whole °C) |
| `com.mohit.dimplex.SET_MODE` | `secret`, `label`, `mode` (`manual`, `eco`, `frost`/`off`, `timer`, or 1–4) |
| `com.mohit.dimplex.OFF` | `secret`, `label` (sets Frost, 7 °C — the heaters have no true off) |
| `com.mohit.dimplex.REFRESH` | `secret`, optional `label` |

`label` matches the heater label case-insensitively; `label:all` (or no label) targets every heater.
In Tasker put extras as `secret:xxxx`, `label:Kitchen`, `temp:21`. Setting a temperature while in
Frost first switches to Eco, then writes the setpoint. Note that Manual vs Eco is decided by the
heater from the setpoint (≤19 → Eco, ≥20 → Manual).

Example with adb:
```
adb shell am broadcast -a com.mohit.dimplex.SET_TEMP -n com.mohit.dimplex/.tasker.TaskerReceiver \
  --es secret xxxx --es label Kitchen --ei temp 21
```

## State broadcast

After every successful poll or command, the app sends `com.mohit.dimplex.STATE` with extras:
`label`, `address`, `ts` (ms), `mode` (int), `mode_name`, `setpoint`, `room_temp` (double),
`heating` (bool), `error` (int). Missing values are omitted.
In Tasker: Event → System → Intent Received, Action `com.mohit.dimplex.STATE`; extras appear as
`%label`, `%room_temp`, etc.

The STATE broadcast is implicit and unprotected: any app can listen. It contains no PINs.

## Future work

A full Tasker plugin (Locale/Tasker plugin API: edit activity + `FIRE_SETTING` receiver, and
state conditions via `QUERY_CONDITION`) would give proper action UIs and variables without the
shared secret.
