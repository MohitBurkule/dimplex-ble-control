# Roadmap

## Floorplan (placeholder screen exists: `ui/Floorplan.kt`)

Goal: show heaters and the user's estimated position on a floorplan of the home.

Data model:
- Floorplan image imported via SAF, copied into app-private storage (`files/floorplan.png`),
  plus its pixel width/height.
- Per heater: `x`, `y` normalized to 0..1 of the image (already reserved as optional fields in
  `HeaterConfig`, persisted in the private heaters file).
- Position estimate: from the `rssi` table (advertisement samples), weighted centroid of heater
  positions using recent per-heater mean RSSI (e.g. weight = 10^(rssi/20)); later a fingerprinting
  approach (record labelled RSSI vectors per room, kNN).

UI: drag heaters onto the image; overlay a dot for the estimate and per-heater RSSI badges.

## Other
- Full Tasker plugin (see docs/tasker.md).
- Timer schedule editing, boost, away mode (0x1027, 0x1028, 0x102a).
- Release signing for non-debug APKs.
