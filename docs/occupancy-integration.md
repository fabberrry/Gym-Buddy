# Local Fit-Ai-IoT occupancy integration

Gym-Buddy reads the absolute `count` from `GET /v1/state`. It never reconstructs
occupancy from entry/exit events. The Home card polls while Home is visible;
camera analysis is independent.

## Configure the debug app

The emulator default is `http://10.0.2.2:8080/`, with device `counter-01` and
room `room-01`. Override these at build time with Gradle properties:

```powershell
.\gradlew.bat :app:assembleDebug '-PgymOccupancyBaseUrl=http://192.168.1.50:8080/'
```

For a physical phone, replace the example address with the laptop's LAN IP and
set `host` in `F:\DEV\repo\Fit-Ai-IoT\config\gateway.json` to `0.0.0.0`.
Put the phone and laptop on the same LAN and allow the gateway port through the
laptop firewall. If the gateway uses different identities, pass
`-PgymOccupancyDeviceId=...` and `-PgymOccupancyRoomId=...` too. No token is
required by the default gateway configuration. Do not put secrets in this repo.

If the emulator cannot connect to `10.0.2.2` on this Windows machine, build the
debug app with `-PgymOccupancyBaseUrl=http://127.0.0.1:8080/` and connect the
emulator to the local gateway with:

```powershell
& 'G:\MobileSdk\platform-tools\adb.exe' reverse tcp:8080 tcp:8080
```

Repeat the reverse command after restarting the emulator.

## Change the count without hardware

For manual increments and decrements, start Gym-Buddy's local demo feed in a
terminal from this repository:

```powershell
python scripts/demo_gym_iot.py --port 8081 --count 0
```

The debug app installed on the Pixel emulator uses `127.0.0.1:8080`; point its
ADB tunnel at the demo feed in another terminal:

```powershell
& 'G:\MobileSdk\platform-tools\adb.exe' reverse --remove tcp:8080
& 'G:\MobileSdk\platform-tools\adb.exe' reverse tcp:8080 tcp:8081
```

Run these commands to change the value while the server stays open:

```powershell
curl.exe -X POST http://127.0.0.1:8081/dev/increment
curl.exe -X POST http://127.0.0.1:8081/dev/decrement
curl.exe http://127.0.0.1:8081/v1/state
```

Each POST changes the absolute count by one and returns the resulting JSON.
Decrementing at zero returns HTTP 409 and leaves zero displayed. Home picks up
the change automatically within three seconds, or immediately when you tap
**Refresh count**. The demo feed only listens on this computer's loopback
address and does not use physical sensors. Press Ctrl+C to stop it.

To reconnect the emulator to the Fit-Ai-IoT gateway on port 8080, run
`& 'G:\MobileSdk\platform-tools\adb.exe' reverse --remove tcp:8080` followed
by `& 'G:\MobileSdk\platform-tools\adb.exe' reverse tcp:8080 tcp:8080`.

The Fit-Ai-IoT desktop gateway publishes simulated RoomState snapshots. From
`F:\DEV\repo\Fit-Ai-IoT`, run:

```powershell
python desktop/people_counter_gateway.py --scenario slow-demo
```

With Home open, the count progresses through `0 → 1 → 2 → 3 → 2 → 3` over about
30 seconds. The app polls every three seconds. Tap **Refresh count** on Home to
request the current snapshot immediately.

To show a fixed count of your choice, stop the gateway with Ctrl+C, set
`initialCount` to that number and `scenario` to `empty` in
`F:\DEV\repo\Fit-Ai-IoT\config\gateway.json`, then run
`python desktop/people_counter_gateway.py` again. For example,
`"initialCount": 5` and `"scenario": "empty"` will keep the displayed count at
5. The app marks a new gateway session with “Counter restarted; verify
baseline.” Restore `initialCount` to 0 and `scenario` to `demo` when finished.

Only debug builds allow local cleartext HTTP. Release builds retain Android's
normal secure networking defaults, so a release deployment needs HTTPS.

## Test with the real desktop simulator

1. In `F:\DEV\repo\Fit-Ai-IoT`, run `./scripts/test.ps1` to build and test the
   gateway. Start `python desktop/people_counter_gateway.py --scenario empty`.
2. In another terminal, run `curl.exe --fail http://127.0.0.1:8080/v1/state`.
   Confirm that `count` is `0`, `status` is `valid`, and the timestamp advances.
3. Install the Gym-Buddy debug build and open Home. The card should change from
   “Checking gym occupancy...” to “0 people in gym.” Open Quick Analyze and
   return: the camera flow should work, and Home should resume polling.
4. Stop the gateway. Home should retain `0` and show a stale/connection message.
5. Restart the gateway with `--scenario entry`; after its scenario completes,
   `curl.exe` and the card should show `1`. Restart with `--scenario entries` to
   show `3`. A changed session ID makes the card flag the reset.
6. Run `--scenario demo` to exercise entry, entry, ambiguity, exit in one
   session. The final count is `1` with `status: uncertain`; the card should show
   the count plus an uncertainty note. The scenario runs faster than the app's
   three-second poll, so intermediate `1 → 2 → 1` snapshots may be skipped.
7. Stop and restart the gateway while Home remains visible. The card should
   retain the last value during the outage and recover automatically.

The default gateway rejects stale producer data after five seconds. The app
also checks the snapshot UTC timestamp and monotonic time since the last new
revision. In the same session, older or duplicate sequences cannot overwrite
the displayed count. A new session is accepted as a new absolute baseline and
flagged for operator review.

**Observed simulator issue:** On this Windows setup, two separate gateway runs
produced the same `sessionId` even though the contract says it changes on each
start. If a restarted gateway reuses the ID and restarts at a lower sequence,
the app correctly rejects those lower revisions under the v1 contract. The
gateway should generate a unique session ID on each launch before restart
recovery can be relied on; until then, its sequence may need to catch up.
