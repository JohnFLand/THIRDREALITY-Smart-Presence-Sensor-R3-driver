# THIRDREALITY Smart Presence Sensor R3 Hubitat Driver

Hubitat Elevation driver for the **THIRDREALITY Smart Presence Sensor R3**, model **3RPL01084Z**.

**Driver version:** 0.19  
**Device type:** Zigbee 3.0, 60 GHz mmWave presence sensor/router  
**Known recent device firmware:** 1.00.40

> This is a community driver and is not an official THIRDREALITY product. Device behavior may vary by firmware version, and THIRDREALITY documentation may lag behind released firmware.

## Features

The driver supports:

- mmWave presence reporting through Hubitat's standard `MotionSensor` capability.
- A separate `occupancy` attribute using `occupied` and `clear`.
- Illuminance reporting with optional event filtering.
- TVOC reporting in ppb.
- The retained `AirQualityIndex` attribute for compatibility with existing automations.
- A derived air-quality status.
- RGB light control.
- Driver-controlled flashing through Hubitat's standard `Flash` capability.
- A confirmed-working device-side RGB Color Loop effect.
- Color-temperature control from 2000 K to 6500 K.
- Light level, transition, startup, and power-restoration settings.
- Newer-firmware radar and TVOC configuration.
- Manual TVOC calibration-baseline reset.
- Zigbee OTA firmware-update requests.
- Firmware-version and driver-version attributes.
- Optional automatic refresh.
- Optional driver-side motion/presence clear timeout.

## Requirements

- Hubitat Elevation with Zigbee enabled.
- THIRDREALITY Smart Presence Sensor R3, model `3RPL01084Z`.
- The v0.19 Groovy driver file.
- Recent device firmware for the manufacturer-specific radar and TVOC controls.

Core motion, illuminance, TVOC, and light functions may work on older firmware. Newer settings can be rejected by older firmware with Zigbee status `0x86`, meaning that the attribute is not supported.

Firmware 1.00.40 has been observed on an updated sensor, although THIRDREALITY's published documentation may still describe an earlier version.

## Installation

### Install the driver code

1. In Hubitat, open **Drivers Code**.
2. Select **New Driver**.
3. Paste the complete contents of `ThirdReality_Smart_Presence_Sensor_R3_Driver_v0_19_RGB_Flash_Test.groovy`.
4. Select **Save**.

### Assign the driver to an existing sensor

1. Open **Devices**.
2. Select the THIRDREALITY Smart Presence Sensor R3.
3. In the **Type** list, select **THIRDREALITY Smart Presence Sensor R3** under the user-driver section.
4. Select **Save Device**.
5. Run **Configure** once.
6. Wait for the commands to complete, then run **Refresh**.

Running **Configure** establishes Zigbee bindings and reporting. Running **Refresh** reads the current sensor, light, firmware, and supported device-setting values.

### Pair a new sensor

For the most reliable initial setup:

1. Install the driver before pairing.
2. Put Hubitat into Zigbee pairing mode.
3. Factory-reset or place the sensor into pairing mode according to the manufacturer's instructions.
4. After discovery, verify that Hubitat selected this driver.
5. Run **Configure**, followed by **Refresh**.

If the device pairs with a generic driver, manually select this driver and then run **Configure**.

## Updating an existing installation

1. Replace the existing driver code with v0.19.
2. Save the driver.
3. Open the device page and select **Save Device**.
4. Run **Configure** after upgrading from v0.16 or an earlier driver.
5. Run **Refresh**.

When upgrading from v0.17, v0.18, or another closely related revision, **Save Device** is normally sufficient for the new Flash and Color Loop controls. Running **Configure** and **Refresh** is still safe and is recommended whenever the device's reports or displayed attributes appear incomplete.

Existing preferences, events, and automations using `motion`, `occupancy`, `illuminance`, `AirQualityIndex`, `tvoc`, or the normal light capabilities should remain usable.

Version 0.17 introduced the separate `tvoc` attribute in ppb while retaining `AirQualityIndex` for compatibility. Version 0.19 adds software-controlled flashing and retains the confirmed-working device-side Color Loop effect.

## Important settings workflow

### Saving preferences does not write device settings

The driver deliberately separates Hubitat preference editing from writes to the physical sensor.

Selecting **Save Preferences** or **Save Device**:

- Saves driver-side options.
- Updates schedules such as automatic refresh.
- Does **not** automatically write radar, TVOC alarm, or light startup settings to the sensor.

This avoids unintentionally overwriting settings when installing or updating the driver.

### Apply Device Settings

Run **Apply Device Settings** only after reviewing the preferences you want written to the sensor.

The command can write populated values for:

- Radar detection distance.
- Radar motion sensitivity.
- Radar stationary-presence sensitivity.
- Device presence exit/hold-time level.
- TVOC alarm threshold.
- TVOC alarm-light enable state.
- Light power-restoration behavior.
- Ordinary On level.
- Startup level after power restoration.
- Device-default on/off transition.
- Startup color temperature.

After writing, the driver reads the settings back. The current values should then appear in the device attributes and supported preference fields.

A recommended workflow is:

1. Run **Refresh**.
2. Review the values populated from the device.
3. Change only the desired preferences.
4. Select **Save Preferences**.
5. Run **Apply Device Settings**.
6. Run **Refresh** again if verification is needed.

Leaving a non-Boolean preference blank generally leaves that device setting unchanged.

## Commands

### Configure

Establishes Zigbee bindings and reporting for:

- Switch state.
- Light level.
- Hue and saturation.
- Color temperature and color mode.
- Illuminance.
- Occupancy.
- Manufacturer-specific radar/TVOC cluster.

It then performs a refresh.

Run **Configure** after:

- Initially assigning the driver.
- Re-pairing the sensor.
- Moving the sensor to another Hubitat hub.
- A major driver update.
- Troubleshooting missing reports.

`Configure` does not intentionally apply the configurable radar, alarm, or startup preferences.

### Refresh

Reads:

- Firmware version.
- Light switch and level.
- Hue, saturation, color temperature, and color mode.
- Lighting startup and power-restoration settings.
- Illuminance.
- Occupancy.
- TVOC.
- Supported radar and TVOC configuration attributes.

Because the driver reads configuration back from the sensor, **Refresh may update the corresponding preference fields** to match the device.

Automatic refresh is optional. Normal Zigbee reporting should usually make frequent polling unnecessary.

### Apply Device Settings

Writes the currently populated device-configuration preferences to the sensor, then reads them back.

This is the command to use after deliberately changing:

- Radar settings.
- TVOC threshold or alarm-light behavior.
- Light startup or power-restoration settings.

Older firmware may reject one or more settings. Enable debug logging and examine the Hubitat logs when testing firmware-dependent features.

### Reset TVOC Calibration

Runs the `resetTVOCCalibration` command, which writes the calibration-reset value to the sensor's manufacturer-specific TVOC calibration attribute.

Use this command only when a new TVOC baseline is intentionally required, such as after:

- Moving the sensor to a substantially different environment.
- Persistent readings that appear implausible.
- A firmware update that appears to have affected the TVOC baseline.
- Manufacturer instructions to recalibrate.

Recommended procedure:

1. Place the sensor in a stable, normally ventilated environment representative of the desired clean-air baseline.
2. Avoid using the command during a temporary pollution event, active cleaning, painting, cooking, solvent use, or other unusual VOC exposure.
3. Run **Reset TVOC Calibration** once.
4. Leave the sensor powered and undisturbed while its readings settle.
5. Use **Refresh** later if a new TVOC report has not yet appeared.

The driver does not run calibration automatically. Repeated calibration resets can prevent the sensor from maintaining a useful long-term baseline.

The command resets the sensor's TVOC baseline; it does not reset Hubitat event history, driver preferences, radar settings, or lighting settings.

### Update Firmware

Requests a Zigbee OTA firmware check through Hubitat.

Availability depends on:

- Hubitat's Zigbee OTA support.
- The firmware image being available to the hub.
- THIRDREALITY's OTA distribution.
- The sensor remaining connected and powered.

Do not disconnect power during a firmware update. After an update, run **Configure** and **Refresh**. Then verify the `firmwareVersion` attribute and review any newly supported settings.

Invoking the command does not guarantee that a newer image is available or that Hubitat will immediately install one.


### Flash

Runs Hubitat's standard `Flash` capability.

The R3 did not visibly respond to the standard Zigbee Identify-cluster Blink effect during testing with firmware 1.00.40. Version 0.19 therefore implements Flash by alternating immediate Zigbee On and Off commands in the driver.

The driver accepts either:

- `flash()` — uses **Default software flash interval**.
- `flash(rateToFlash)` — uses the supplied interval in milliseconds.

The supported interval is 500–30,000 milliseconds. Values below 500 milliseconds are raised to 500 milliseconds to limit Zigbee traffic.

The interval is the time between state changes. For example, a value of `1000` produces approximately one second On, followed by one second Off.

When Flash begins:

- An active Color Loop is stopped.
- The light's current On/Off state is remembered.
- The existing steady color, color temperature, and level are not intentionally changed.
- An automatic safety-stop timer is started.

Because this effect is generated by repeated commands from Hubitat, it depends on the hub and Zigbee connection remaining available. It is better suited to occasional alerts than to long-running decorative use.

### Stop Flash

Stops driver-controlled flashing and restores the On/Off state that existed immediately before Flash was started.

Active flashing is also canceled when any of these commands is used:

- On or Off.
- Set Level.
- Start or Stop Level Change.
- Set Color.
- Set Hue or Saturation.
- Set Color Temperature.
- Start Color Loop.
- Saving the device preferences.

When one of those commands intentionally changes the light, the new command takes precedence rather than restoring the pre-flash state.

### Start Color Loop

Starts the R3's device-side Zigbee Color Loop effect.

Color Loop was confirmed to work on device firmware 1.00.40. Unlike software Flash, the loop runs within the light after the start command has been sent.

The effect uses two driver preferences:

- **Color-loop cycle time** — seconds for one complete hue cycle.
- **Color-loop direction** — increasing or decreasing hue.

For the effect to be readily visible, use a nonzero saturation and a moderate or high light level. A saturation of zero produces white light, so changes in hue may not be visible.

Starting Color Loop stops driver-controlled flashing first.

During a loop, the R3 can report frequent hue, color-name, and illuminance changes. This is normal, but long-running loops can create a large number of Hubitat events and log entries.

### Stop Color Loop

Stops the device-side Color Loop without intentionally changing the steady color reached at the moment the loop is stopped.

The standard Zigbee Identify effects—Blink, Breathe, Okay, Channel Change, Finish Effect, and Stop Effect—were tested on firmware 1.00.40. The commands were accepted without a Zigbee error but produced no visible effect, so they are not exposed by v0.19.


## Radar and presence settings

### Radar detection distance

Sets the nominal detection range from 1 to 6 meters.

A longer distance can increase coverage but may also detect movement or presence outside the intended area.

### Radar motion sensitivity

Range: 0–20.

Higher values increase sensitivity to moving targets.

### Radar stationary-presence sensitivity

Range: 0–20.

Higher values increase sensitivity to small movements or relatively stationary occupants. Excessively high values may increase false presence.

### Device presence exit/hold-time level

The driver currently exposes levels 1–4:

- `1` is the shortest.
- `4` is the longest.

This is a device-side delay controlled by the sensor firmware. The exact duration represented by each level is not exposed by the driver.

### Driver motion/presence clear timeout

This is separate from the device-side hold-time level.

- `0`: Follow the sensor's clear reports only.
- Greater than `0`: Force `motion = inactive` and `occupancy = clear` after the selected number of seconds since the most recent occupied report.

Normally, use the device-side presence hold time first. The driver timeout is useful as a fallback when a sensor occasionally remains active longer than desired or a clear report is missed.

Setting the driver timeout shorter than the sensor's own hold time can cause Hubitat to show clear while the sensor still internally considers the space occupied.

## Motion and occupancy attributes

The same radar state is presented in two forms:

- `motion`: `active` or `inactive`, for compatibility with Hubitat apps and Rule Machine.
- `occupancy`: `occupied` or `clear`, for users who prefer presence-oriented terminology.

These are not separate radar zones or separate physical measurements.

## TVOC and air-quality reporting

### `tvoc`

The preferred numeric attribute. It reports the sensor value in **ppb**.

### `AirQualityIndex`

Retained for compatibility with existing automations created for earlier driver versions.

It contains the same numeric value as `tvoc` and is labeled in ppb. It is not a standardized AQI calculation.

### `AirQuality`

The driver derives a descriptive status using the device instruction-sheet thresholds:

| TVOC value | Status |
|---:|---|
| 0 | `possible error` |
| 1–500 | `good` |
| 501–1000 | `ventilate` |
| 1001–3000 | `warning` |
| Above 3000 | `danger` |

A value of `0` is retained rather than discarded. The device appears able to report zero, but zero may also represent unavailable, uninitialized, or invalid data. The driver therefore publishes the measurement and labels the status `possible error`.

### TVOC event filtering

Two preferences reduce unnecessary Hubitat events:

- **TVOC / Air Quality Index deadband**
- **Minimum seconds between TVOC / Air Quality Index reports**

A status-band change is published even when the normal numeric filtering criteria have not been met.

Set the deadband or minimum interval to zero when raw, frequent reporting is required.

## TVOC alarm settings

### TVOC alarm threshold

Range: 3000–50,000 ppb.

This is the threshold used by the sensor's own alarm-light behavior. It does not change the driver's `AirQuality` status thresholds.

### TVOC alarm light

Enables or disables the sensor's device-side warning light when the threshold is exceeded.

The exact visual behavior is controlled by the sensor firmware.

## Illuminance reporting

Illuminance is reported in lux.

The driver supports:

- An illuminance deadband.
- A minimum interval for smaller changes.

Large changes meeting the deadband are published immediately. Smaller nonzero changes can be published after the minimum interval.

These are driver-side filters and do not change the physical sensor's measurement behavior.

## Light control

The integrated light supports:

- On and off.
- Level.
- Continuous level change.
- Hue and saturation.
- RGB color.
- Color temperature.
- Color mode.
- Driver-controlled software Flash.
- Device-side Color Loop.
- Configurable command-transition times.
- Optional color staging while off.
- Minimum nonzero level.
- Startup and power-restoration settings.

### Command transition settings

The separate driver transition preferences control commands sent by Hubitat:

- Level transition time.
- On transition time.
- Off transition time.
- RGB transition time.
- Color-temperature transition time.

These are distinct from **Device default on/off transition**, which is written to the light's standard Zigbee attribute and may affect behavior initiated outside the driver's explicit commands.

### Minimum level

Requests above 0% but below the configured minimum are raised to the minimum value.

A request for 0% remains 0 and turns the light off.

### Color staging while off

When enabled, RGB or color-temperature changes can be sent while leaving the light off.

When disabled, setting a color while the light is off normally turns it on.

### High-resolution hue

When disabled, hue uses Hubitat's usual 0–100 percentage range.

When enabled, hue is represented in degrees from 0–360.

Changing this preference can affect automations that directly compare or set numeric hue values.

## RGB flashing and effects

### Software Flash preferences

#### Default software flash interval

Range: 500–30,000 milliseconds. Default: 1000 milliseconds.

This value is used when an app or the device page invokes `flash()` without supplying a rate. Hubitat apps may instead call `flash(rateToFlash)` with their own interval.

A shorter interval creates more Zigbee traffic. The driver enforces a minimum of 500 milliseconds.

#### Software flash automatic stop

Range: 1–30 minutes. Default: 10 minutes.

The safety timer prevents an accidentally started flash from continuing indefinitely. **Stop Flash** or any normal light-changing command stops it sooner.

### Color Loop preferences

#### Color-loop cycle time

Range: 1–65,535 seconds. Default: 10 seconds.

This is the approximate time for one complete trip around the hue range.

#### Color-loop direction

Selects increasing or decreasing hue.

Color Loop is handled by the R3 itself and was confirmed on firmware 1.00.40.

### Flash versus Color Loop

| Feature | Execution | Pattern | Stops automatically |
|---|---|---|---|
| Flash | Repeated commands from Hubitat | Current light alternates On and Off | Yes, by the configured safety timer |
| Color Loop | Runs inside the R3 | Hue continuously cycles | No; use **Stop Color Loop** |

## Light startup and restoration settings

### Light behavior after power is restored

Options:

- Off.
- On.
- Toggle.
- Previous.

### Ordinary On level

The level used by an ordinary On command, where supported by the device.

This is different from the driver's remembered last nonzero level used when constructing its own transition command.

### Startup level

The level used after power is physically restored.

### Startup color temperature

The color temperature used after power restoration, from 2000 K to 6500 K.

### Device default on/off transition

A standard Zigbee transition value stored in the light. It is separate from the driver's individual On, Off, level, RGB, and color-temperature transition preferences.

## Attributes

Commonly useful attributes include:

| Attribute | Values or units |
|---|---|
| `motion` | `active`, `inactive` |
| `occupancy` | `occupied`, `clear` |
| `illuminance` | lux |
| `tvoc` | ppb |
| `AirQualityIndex` | compatibility value in ppb |
| `AirQuality` | `possible error`, `good`, `ventilate`, `warning`, `danger` |
| `switch` | `on`, `off` |
| `level` | percent |
| `hue` | percent or degrees |
| `saturation` | percent |
| `colorTemperature` | kelvin |
| `colorMode` | `RGB`, `CT` |
| `colorName` | descriptive color name |
| `firmwareVersion` | device-reported firmware/build string |
| `driverVersion` | driver version |
| `radarDetectionDistance` | meters |
| `radarMotionSensitivity` | 0–20 |
| `radarPresenceSensitivity` | 0–20 |
| `radarPresenceHoldTime` | 1–4 |
| `tvocAlertThreshold` | ppb |
| `tvocAlertEnabled` | `enabled`, `disabled` |

## Logging

### Description-text logging

Produces normal informational messages when reportable states change.

### Debug logging

Provides detailed parsing, filtering, command, and manufacturer-specific attribute information.

Debug logging automatically disables after approximately 30 minutes.

Enable debug logging temporarily when:

- Testing new firmware features.
- Checking whether a setting write was accepted.
- Diagnosing missing reports.
- Investigating TVOC data encoding.
- Reporting a driver issue.

## Firmware compatibility

The manufacturer-specific settings depend on sensor firmware.

When an attribute is unavailable, the logs may show:

```text
Radar/TVOC attribute 0xF00x is not supported by the installed firmware.
```

or a write rejection with status `0x86`.

This does not necessarily indicate a driver failure. It means that the installed firmware did not expose that particular attribute.

Firmware 1.00.40 has been observed to be available even while public documentation still referenced an earlier release. The driver reads the device's reported firmware string rather than assuming a fixed version.

## Troubleshooting

### Firmware version remains blank

1. Run **Refresh**.
2. Enable debug logging.
3. Confirm that the sensor is online and responding.
4. Run **Configure**, then **Refresh** again.

Some firmware may format or expose the Basic-cluster build string differently.

### New radar or TVOC preferences do not work

1. Confirm that the sensor has recent firmware.
2. Run **Refresh** to read supported values.
3. Save the desired preferences.
4. Run **Apply Device Settings**.
5. Review the logs for status `0x86` or another Zigbee error.
6. Run **Refresh** to verify the readback.

### Saving a preference did not change the sensor

This is intentional. Run **Apply Device Settings** after saving the preference.

### Preferences changed after Refresh

The driver updates supported preference fields with values read from the physical sensor. This keeps the Hubitat page aligned with the device's actual configuration.

### Motion remains active too long

- Reduce the device presence hold-time level and run **Apply Device Settings**.
- As a fallback, set a driver motion/presence clear timeout.

### Motion clears too soon

- Increase the device presence hold-time level.
- Reduce sensitivity only if false detections are also occurring.
- Disable the driver timeout by setting it to `0` if it is overriding valid presence.

### Excessive illuminance or TVOC events

Increase the corresponding deadband or minimum-report interval.

### TVOC is zero

The driver publishes zero and sets `AirQuality` to `possible error`.

Possible explanations include:

- A legitimate sensor output.
- Sensor warm-up or unavailable data.
- An unsettled calibration baseline.
- A firmware or reporting issue.

Avoid immediately and repeatedly resetting calibration. First leave the sensor powered, observe subsequent reports, and use **Refresh**.

### TVOC appears implausibly high or low

- Confirm the units are ppb.
- Allow the sensor to remain powered in a stable environment.
- Check for nearby VOC sources.
- Review debug logs for the raw Zigbee encoding.
- Use **Reset TVOC Calibration** only when a new baseline is genuinely appropriate.

### Light state does not update after a command

The driver performs delayed attribute reads after transitions. Run **Refresh** if the displayed state still appears stale.

### Color commands unexpectedly turn on the light

Enable **Color pre-staging while off** when colors should be changed without illuminating the light.

### Flash reports a `MissingMethodException`

Versions before v0.19 did not accept the optional numeric interval that Hubitat may pass to `flash(rateToFlash)`. Install v0.19 or later, select **Save Device**, and try again.

### Flash does not start

- Confirm that v0.19 is assigned to the device.
- Select **Save Device** after updating the code.
- Try the **Flash** command directly from the device page.
- Use an interval of at least 500 milliseconds.
- Enable debug logging and verify that the device is online.
- Stop any existing Color Loop and retry, although Flash normally stops it automatically.

### Flash continues longer than desired

Run **Stop Flash**, use a normal On/Off or color command, or reduce **Software flash automatic stop**.

### Flash creates too much Zigbee traffic

Increase the flash interval or use Flash only for short alerts. Flash is implemented by repeated On/Off commands from the hub because the R3's Zigbee Identify Blink effect did not work during testing.

### Color Loop does not appear to change color

- Turn the light on.
- Set saturation above zero.
- Use a moderate or high level.
- Try a cycle time of approximately 10 seconds.
- Run **Start Color Loop** again.
- Confirm that the device firmware is recent; the effect was verified on 1.00.40.

### Color Loop creates many events or log entries

The sensor reports hue, color-name, and illuminance changes while the effect runs. Stop the loop when testing is complete, or disable description-text logging to reduce log output.

### Blink, Breathe, Okay, or Channel Change effects are missing

This is intentional. The standard Zigbee Identify effects were tested on firmware 1.00.40 and produced no visible response. Only Color Loop was confirmed as a working device-side effect.

## Known limitations and notes

- The current driver exposes presence hold-time levels 1–4.
- The meaning of each hold-time level is firmware-defined rather than expressed in seconds.
- `AirQualityIndex` is retained for compatibility but is not a standardized AQI.
- Software Flash is generated by repeated Zigbee commands and is not a self-contained device effect.
- Flash intervals below 500 milliseconds are intentionally not supported.
- Color Loop was confirmed on firmware 1.00.40; behavior may differ on other firmware.
- Standard Zigbee Identify effects produced no visible response on firmware 1.00.40.
- The driver cannot guarantee that Hubitat or THIRDREALITY has an OTA image available.
- Firmware-specific attributes may change in future releases.
- The sensor is a Zigbee router and normally requires continuous USB power.
- The driver is designed for model `3RPL01084Z`; it should not be assumed compatible with other THIRDREALITY presence-sensor models.

## Version 0.19 highlights

- Added Hubitat's standard `Flash` capability.
- Added both `flash()` and `flash(rateToFlash)` method signatures.
- Implemented Flash with driver-controlled immediate On/Off commands because the R3 ignored the Zigbee Identify Blink effect.
- Added **Stop Flash** with restoration of the pre-flash On/Off state.
- Added a configurable default flash interval.
- Added an automatic flash safety stop.
- Made ordinary light commands cancel active flashing.
- Retained and documented the confirmed-working device-side Color Loop.
- Added configurable Color Loop cycle time and direction.
- Removed nonfunctional Identify-effect test commands.
- Retained all v0.17 radar, TVOC, firmware, calibration, OTA, filtering, and lighting functionality.

### Earlier v0.17 additions retained in v0.19

- Newer-firmware radar settings.
- TVOC threshold and alarm-light settings.
- Manual TVOC calibration reset.
- Zigbee OTA update command.
- Firmware-version reporting.
- Color-temperature and color-mode support.
- Startup and power-restoration light settings.
- Independent hue and saturation commands.
- Multi-attribute Zigbee response parsing.
- TVOC and illuminance filtering.
- Driver-side motion clear timeout.
- `AirQualityIndex` compatibility plus `tvoc` in ppb.
- The declared `possible error` air-quality state for a reported value of zero.
- Separation of preference saving from deliberate device-setting writes.
