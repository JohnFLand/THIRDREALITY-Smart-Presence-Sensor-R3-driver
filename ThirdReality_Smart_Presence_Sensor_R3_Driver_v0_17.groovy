/**
 *  THIRDREALITY Smart Presence Sensor R3 (3RPL01084Z)
 *  Version 0.17
 *
 *  Hubitat driver providing:
 *   -  RGB and color-temperature light control
 *   -  mmWave occupancy/presence reporting as MotionSensor plus custom occupancy
 *   -  Illuminance reporting with optional driver-side filtering
 *   -  TVOC reporting from cluster 0x042E as both tvoc (ppb) and the retained
 *      AirQualityIndex compatibility attribute
 *   -  Air-quality status derived from the device instruction-sheet thresholds
 *   -  Optional driver-side adjustable motion/presence clear timeout
 *   -  New-firmware radar, TVOC alarm, and lighting start-up configuration
 *   -  Manual TVOC baseline recalibration and Zigbee OTA update request
 *
 *  Version 0.17 notes:
 *   -  Adds manufacturer-specific controls introduced by newer ThirdReality firmware.
 *   -  Device settings are NOT automatically written when preferences are saved.
 *      Use the Apply Device Settings command after reviewing the preferences.
 *   -  Reads and displays the Basic-cluster software build/firmware version.
 *   -  Keeps value 0 as AirQuality = "possible error" because 0 may represent
 *      either a valid device output or unavailable/invalid TVOC data.
 *   -  Preserves the defensive multi-format TVOC decoder because firmware/platform
 *      combinations have exposed attribute 0x0000 as both integer and float data.
 */

import groovy.transform.Field

@Field static final String DRIVER_VERSION = "0.17"

@Field static final Integer CLUSTER_BASIC        = 0x0000
@Field static final Integer CLUSTER_ON_OFF       = 0x0006
@Field static final Integer CLUSTER_LEVEL        = 0x0008
@Field static final Integer CLUSTER_COLOR        = 0x0300
@Field static final Integer CLUSTER_ILLUMINANCE  = 0x0400
@Field static final Integer CLUSTER_OCCUPANCY    = 0x0406
@Field static final Integer CLUSTER_TVOC         = 0x042E

@Field static final String MFG_CODE = "0x1407"

// Basic cluster
@Field static final Integer ATTR_SW_BUILD_ID = 0x4000

// Manufacturer-specific attributes on cluster 0x042E
@Field static final Integer ATTR_TVOC               = 0x0000
@Field static final Integer ATTR_TVOC_CALIBRATE     = 0xF001
@Field static final Integer ATTR_DETECT_DISTANCE    = 0xF002
@Field static final Integer ATTR_TVOC_THRESHOLD     = 0xF003
@Field static final Integer ATTR_MOTION_SENS        = 0xF004
@Field static final Integer ATTR_PRESENCE_SENS      = 0xF005
@Field static final Integer ATTR_HOLD_TIME          = 0xF006
@Field static final Integer ATTR_TVOC_ALERT_ENABLE  = 0xF007

// Standard lighting attributes
@Field static final Integer ATTR_POWER_ON_BEHAVIOR  = 0x4003 // On/Off cluster
@Field static final Integer ATTR_ONOFF_TRANSITION   = 0x0010 // Level cluster, tenths of a second
@Field static final Integer ATTR_ON_LEVEL           = 0x0011
@Field static final Integer ATTR_STARTUP_LEVEL      = 0x4000
@Field static final Integer ATTR_COLOR_TEMP_MIREDS  = 0x0007
@Field static final Integer ATTR_COLOR_MODE         = 0x0008
@Field static final Integer ATTR_ENHANCED_MODE      = 0x4001
@Field static final Integer ATTR_STARTUP_CT_MIREDS  = 0x4010

@Field static final Integer CT_MIN_MIREDS = 154
@Field static final Integer CT_MAX_MIREDS = 500
@Field static final Integer CT_MIN_KELVIN = 2000
@Field static final Integer CT_MAX_KELVIN = 6500

metadata {
    definition(name: "THIRDREALITY Smart Presence Sensor R3", namespace: "openai v0.17", author: "OpenAI", singleThreaded: true) {
        capability "Actuator"
        capability "Sensor"
        capability "Light"
        capability "Bulb"
        capability "Switch"
        capability "Switch Level"
        capability "ChangeLevel"
        capability "ColorControl"
        capability "ColorTemperature"
        capability "ColorMode"
        capability "MotionSensor"
        capability "IlluminanceMeasurement"
        capability "Refresh"
        capability "Configuration"

        attribute "occupancy", "enum", ["occupied", "clear"]
        attribute "AirQualityIndex", "number"
        attribute "tvoc", "number"
        attribute "AirQuality", "enum", ["possible error", "good", "ventilate", "warning", "danger"]
        attribute "colorName", "string"
        attribute "firmwareVersion", "string"
        attribute "driverVersion", "string"

        attribute "radarDetectionDistance", "number"
        attribute "radarMotionSensitivity", "number"
        attribute "radarPresenceSensitivity", "number"
        attribute "radarPresenceHoldTime", "number"
        attribute "tvocAlertThreshold", "number"
        attribute "tvocAlertEnabled", "enum", ["enabled", "disabled"]

        command "applyDeviceSettings"
        command "resetTVOCCalibration"
        command "updateFirmware"

        fingerprint profileId: "0104", endpointId: "01",
            inClusters: "0000,0003,0004,0005,0006,0008,0012,0300,0400,0406,042E,1000",
            outClusters: "0019",
            manufacturer: "Third Reality, Inc",
            model: "3RPL01084Z",
            deviceJoinName: "THIRDREALITY Smart Presence Sensor R3"
    }

    preferences {
        input name: "detectDistance", type: "enum", title: "Radar detection distance",
            options: [[1:"1 m"], [2:"2 m"], [3:"3 m"], [4:"4 m"], [5:"5 m"], [6:"6 m"]],
            required: false, description: "Newer firmware: effective radar detection range. Refresh reads the current device value."
        input name: "motionSensitivity", type: "number", title: "Radar motion sensitivity",
            range: "0..20", required: false,
            description: "Newer firmware: sensitivity to moving targets; higher is more sensitive."
        input name: "presenceSensitivity", type: "number", title: "Radar stationary-presence sensitivity",
            range: "0..20", required: false,
            description: "Newer firmware: sensitivity to small/stationary presence; higher is more sensitive."
        input name: "presenceHoldTime", type: "enum", title: "Device presence exit/hold-time level",
            options: [[1:"1 (shortest)"], [2:"2"], [3:"3"], [4:"4 (longest)"]],
            required: false,
            description: "Device-side clear delay level. This is separate from the optional driver timeout below."
        input name: "airQualityThreshold", type: "number", title: "TVOC alarm threshold (ppb)",
            range: "3000..50000", required: false,
            description: "Newer firmware: TVOC level at which the device alarm light is triggered."
        input name: "tvocAlertEnable", type: "bool", title: "Enable the device TVOC alarm light",
            required: false,
            description: "Newer firmware: enables the device's red blinking TVOC warning light."

        input name: "powerOnBehavior", type: "enum", title: "Light behavior after power is restored",
            options: [[0:"Off"], [1:"On"], [2:"Toggle"], [255:"Previous"]],
            required: false
        input name: "onLevel", type: "number", title: "Light level used by an ordinary On command (%)",
            range: "1..100", required: false,
            description: "Blank leaves the device's current/default OnLevel unchanged."
        input name: "startUpLevel", type: "number", title: "Light level after power is restored (%)",
            range: "0..100", required: false,
            description: "Blank leaves the current setting unchanged."
        input name: "deviceDefaultTransitionSeconds", type: "number", title: "Device default on/off transition (seconds)",
            range: "0..30", required: false,
            description: "Standard Zigbee default; the separate transition preferences below still govern driver commands."
        input name: "startUpColorTempK", type: "number", title: "Color temperature after power is restored (K)",
            range: "${CT_MIN_KELVIN}..${CT_MAX_KELVIN}", required: false,
            description: "Blank leaves the current setting unchanged."

        input name: "levelTransitionTime", type: "enum", title: "Level transition time (default: 1s)",
            options: [[500:"500 ms"], [1000:"1 s"], [1500:"1.5 s"], [2000:"2 s"], [5000:"5 s"]], defaultValue: 1000
        input name: "startLevelChangeRate", type: "enum", title: "Start level change rate (default: Fast)",
            options: [[25:"Slow"], [50:"Medium"], [100:"Fast"]], defaultValue: 100
        input name: "onTransitionTime", type: "enum", title: "On transition time (default: 1s)",
            options: [[500:"500 ms"], [1000:"1 s"], [1500:"1.5 s"], [2000:"2 s"], [5000:"5 s"]], defaultValue: 1000
        input name: "offTransitionTime", type: "enum", title: "Off transition time (default: 1s)",
            options: [[500:"500 ms"], [1000:"1 s"], [1500:"1.5 s"], [2000:"2 s"], [5000:"5 s"]], defaultValue: 1000
        input name: "rgbTransitionTime", type: "enum", title: "RGB transition time (default: 1s)",
            options: [[500:"500 ms"], [1000:"1 s"], [1500:"1.5 s"], [2000:"2 s"], [5000:"5 s"]], defaultValue: 1000
        input name: "colorTemperatureTransitionTime", type: "enum", title: "Color-temperature transition time (default: 1s)",
            options: [[500:"500 ms"], [1000:"1 s"], [1500:"1.5 s"], [2000:"2 s"], [5000:"5 s"]], defaultValue: 1000
        input name: "minimumLevel", type: "number", title: "Minimum level (default: 5%)",
            description: "Requested levels above 0% but below this minimum are raised to this value", defaultValue: 5, range: "0..100"
        input name: "colorStaging", type: "bool", title: "Enable color pre-staging when light is off", defaultValue: false
        input name: "hiRezHue", type: "bool", title: "Use hue in degrees (0-360) instead of percent", defaultValue: false
        input name: "autoRefreshMinutes", type: "enum", title: "Automatic refresh interval",
            options: [[0:"Disabled"], [1:"Every 1 minute"], [5:"Every 5 minutes"], [10:"Every 10 minutes"], [15:"Every 15 minutes"], [30:"Every 30 minutes"]], defaultValue: 0
        input name: "illuminanceMinDeltaLux", type: "number", title: "Illuminance deadband (Lux)",
            description: "Ignore smaller lux changes than this amount", defaultValue: 3, range: "0..1000"
        input name: "illuminanceMinSeconds", type: "number", title: "Minimum seconds between small illuminance reports",
            description: "Changes smaller than the deadband are ignored until this much time has passed", defaultValue: 30, range: "0..3600"
        input name: "tvocMinDelta", type: "number", title: "TVOC / Air Quality Index deadband",
            description: "Ignore smaller changes unless the Air Quality status band changes", defaultValue: 2, range: "0..10000"
        input name: "tvocMinSeconds", type: "number", title: "Minimum seconds between TVOC / Air Quality Index reports",
            description: "Do not send changed values more often than this unless the status band changes", defaultValue: 30, range: "0..3600"
        input name: "motionClearSeconds", type: "number", title: "Driver motion/presence clear timeout (seconds)",
            description: "0 = follow device clear reports only. Any value > 0 clears motion/occupancy this many seconds after the last occupied report.", defaultValue: 0, range: "0..3600"
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
    }
}

def installed() {
    log.info "installed..."
    publishDriverVersion()
    sendEvent(name: "motion", value: "inactive")
    sendEvent(name: "occupancy", value: "clear")
    unschedule("syntheticMotionClear")
    scheduleAutoRefresh()
}

def updated() {
    log.info "updated..."
    publishDriverVersion()
    log.warn "debug logging is: ${logEnable == true}"
    log.warn "description logging is: ${txtEnable == true}"
    log.warn "driver motion/presence clear timeout is: ${safeToInt(settings.motionClearSeconds, 0)} second(s)"
    log.warn "Device preferences are not written automatically; use Apply Device Settings when ready."
    if (logEnable) runIn(1800, "logsOff")
    if (safeToInt(settings.motionClearSeconds, 0) <= 0) unschedule("syntheticMotionClear")
    scheduleAutoRefresh()
}

def deviceTypeUpdated() {
    if (logEnable) log.debug "deviceTypeUpdated()"
    updated()
    runIn(1, "configure")
}

def logsOff() {
    log.warn "debug logging disabled..."
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

private void publishDriverVersion() {
    device.updateDataValue("driverVersion", DRIVER_VERSION)
    sendEventIfChanged("driverVersion", DRIVER_VERSION, null, null)
}

private void scheduleAutoRefresh() {
    unschedule("refresh")
    Integer mins = safeToInt(settings.autoRefreshMinutes, 0)
    switch (mins) {
        case 1:
            runEvery1Minute("refresh")
            break
        case 5:
            runEvery5Minutes("refresh")
            break
        case 10:
            runEvery10Minutes("refresh")
            break
        case 15:
            runEvery15Minutes("refresh")
            break
        case 30:
            runEvery30Minutes("refresh")
            break
        default:
            break
    }
}

def parse(String description) {
    if (logEnable) log.debug "parse description: ${description}"
    if (!description) return

    Map descMap
    try {
        descMap = zigbee.parseDescriptionAsMap(description)
    } catch (Exception e) {
        log.warn "Unable to parse Zigbee message: ${description}; ${e.message}"
        return
    }
    if (logEnable) log.debug "descMap: ${descMap}"
    if (!descMap) return

    Integer clusterInt = getClusterInt(descMap)
    Integer commandInt = descMap.commandInt != null ? safeToInt(descMap.commandInt, -1) : hexToInt(descMap.command, -1)

    // Write Attributes Response for manufacturer-specific radar configuration.
    if (clusterInt == CLUSTER_TVOC && descMap.attrId == null && commandInt == 0x04) {
        reportRadarWriteStatus(descMap.data as List)
        return
    }

    if (descMap.attrId != null || descMap.attrInt != null) {
        parseAttributeReport(descMap)
        descMap.additionalAttrs?.each { Map additional ->
            Map merged = [:]
            merged.putAll(additional)
            if (merged.cluster == null) merged.cluster = descMap.cluster
            if (merged.clusterId == null) merged.clusterId = descMap.clusterId
            if (merged.clusterInt == null) merged.clusterInt = clusterInt
            parseAttributeReport(merged)
        }
        return
    }

    if (logEnable) log.debug "Unhandled Zigbee message: cluster=${descMap.clusterId ?: descMap.cluster}, command=${descMap.command}, data=${descMap.data}"
}

private void parseAttributeReport(Map descMap) {
    Integer clusterInt = getClusterInt(descMap)
    Integer attrInt = getAttrInt(descMap)

    if (clusterInt == null || attrInt == null) {
        if (logEnable) log.debug "Attribute report missing cluster/attribute: ${descMap}"
        return
    }

    if (descMap.value == null) {
        String status = descMap.status?.toString()?.toUpperCase()
        if (clusterInt == CLUSTER_TVOC && status == "86") {
            log.warn "Radar/TVOC attribute 0x${zigbee.convertToHexString(attrInt, 4)} is not supported by the installed firmware."
        } else if (logEnable) {
            log.debug "No value for cluster 0x${zigbee.convertToHexString(clusterInt, 4)} attr 0x${zigbee.convertToHexString(attrInt, 4)} status=${status}"
        }
        return
    }

    switch (clusterInt) {
        case CLUSTER_BASIC:
            if (attrInt == ATTR_SW_BUILD_ID) handleFirmwareVersion(descMap.value)
            break

        case CLUSTER_ON_OFF:
            if (attrInt == 0x0000) {
                String value = hexToInt(descMap.value, 0) == 1 ? "on" : "off"
                String descriptionText = (device.currentValue("switch") == value) ?
                    "${device.displayName} is ${value}" : "${device.displayName} was turned ${value}"
                sendEventIfChanged("switch", value, descriptionText, null)
            } else if (attrInt == ATTR_POWER_ON_BEHAVIOR) {
                Integer raw = hexToInt(descMap.value, -1)
                if (raw >= 0) updateSettingIfDifferent("powerOnBehavior", raw.toString(), "enum")
            }
            break

        case CLUSTER_LEVEL:
            switch (attrInt) {
                case 0x0000:
                    Integer level = Math.round(hexToInt(descMap.value, 0) * 100 / 254.0)
                    if (level > 0) state.lastNonZeroLevel = level
                    String descriptionText = (safeToInt(device.currentValue("level"), -1) == level) ?
                        "${device.displayName} is ${level}%" : "${device.displayName} was set to ${level}%"
                    sendEventIfChanged("level", level, descriptionText, "%")
                    break
                case ATTR_ONOFF_TRANSITION:
                    BigDecimal seconds = BigDecimal.valueOf(hexToInt(descMap.value, 0) / 10.0d).stripTrailingZeros()
                    updateSettingIfDifferent("deviceDefaultTransitionSeconds", seconds.toPlainString(), "number")
                    break
                case ATTR_ON_LEVEL:
                    Integer raw = hexToInt(descMap.value, 0xFF)
                    if (raw != 0xFF) {
                        Integer pct = Math.max(1, Math.min(100, Math.round(raw * 100 / 254.0d) as Integer))
                        updateSettingIfDifferent("onLevel", pct, "number")
                    }
                    break
                case ATTR_STARTUP_LEVEL:
                    Integer raw = hexToInt(descMap.value, 0xFF)
                    if (raw != 0xFF) {
                        Integer pct = raw == 0 ? 0 : Math.max(1, Math.min(100, Math.round(raw * 100 / 254.0d) as Integer))
                        updateSettingIfDifferent("startUpLevel", pct, "number")
                    }
                    break
            }
            break

        case CLUSTER_COLOR:
            parseColorCluster(descMap)
            break

        case CLUSTER_ILLUMINANCE:
            if (attrInt == 0x0000) {
                Integer raw = hexToInt(descMap.value, 0)
                Integer lux = raw > 0 ? Math.round(Math.pow(10, ((raw - 1) / 10000.0d))) : 0
                handleIlluminanceReport(lux)
            }
            break

        case CLUSTER_OCCUPANCY:
            if (attrInt == 0x0000) {
                Integer raw = hexToInt(descMap.value, 0)
                handleOccupancyReport((raw & 0x01) == 0x01)
            }
            break

        case CLUSTER_TVOC:
            if (attrInt == ATTR_TVOC) {
                BigDecimal tvocValue = parseTvocValue(descMap)
                if (logEnable) log.debug buildTvocDebugLine(descMap, tvocValue)
                if (tvocValue != null) {
                    handleTvocReport(tvocValue.setScale(0, BigDecimal.ROUND_HALF_UP).toInteger())
                } else if (logEnable) {
                    log.debug "Unable to parse TVOC value from enc=${descMap.encoding ?: '??'} hex=${descMap.value}"
                }
            } else {
                parseRadarConfigAttribute(attrInt, descMap.value)
            }
            break

        default:
            if (logEnable) log.debug "Ignoring cluster 0x${zigbee.convertToHexString(clusterInt, 4)} attr 0x${zigbee.convertToHexString(attrInt, 4)}"
            break
    }
}

private void parseColorCluster(Map descMap) {
    Integer attrInt = getAttrInt(descMap)
    Integer rawValue = hexToInt(descMap.value, 0)
    String descriptionText
    String name
    Object value
    String unit

    switch (attrInt) {
        case 0x0000: // hue
            if (hiRezHue) {
                value = Math.round(rawValue * 360 / 254)
                if ((value as Integer) == 361) value = 360
                unit = "°"
            } else {
                value = Math.round(rawValue / 254 * 100)
                unit = "%"
            }
            name = "hue"
            state.lastHue = descMap.value
            descriptionText = (safeToInt(device.currentValue(name), -999) == (value as Integer)) ?
                "${device.displayName} ${name} is ${value}${unit}" :
                "${device.displayName} ${name} was set to ${value}${unit}"
            break

        case 0x0001: // saturation
            value = Math.round(rawValue / 254 * 100)
            unit = "%"
            name = "saturation"
            state.lastSaturation = descMap.value
            descriptionText = (safeToInt(device.currentValue(name), -999) == (value as Integer)) ?
                "${device.displayName} ${name} is ${value}${unit}" :
                "${device.displayName} ${name} was set to ${value}${unit}"
            break

        case ATTR_COLOR_TEMP_MIREDS:
            if (rawValue <= 0 || rawValue == 0xFFFF) {
                if (logEnable) log.debug "Color temperature is undefined/sentinel: ${rawValue}"
                return
            }
            Integer kelvin = miredsToKelvin(rawValue)
            name = "colorTemperature"
            value = kelvin
            unit = "K"
            descriptionText = "${device.displayName} color temperature is ${kelvin}K"
            setColorTemperatureName(kelvin)
            break

        case ATTR_COLOR_MODE:
        case ATTR_ENHANCED_MODE:
            value = rawValue == 2 ? "CT" : "RGB"
            name = "colorMode"
            descriptionText = "${device.displayName} color mode is ${value}"
            break

        case ATTR_STARTUP_CT_MIREDS:
            if (rawValue > 0 && rawValue != 0xFFFF) {
                updateSettingIfDifferent("startUpColorTempK", miredsToKelvin(rawValue), "number")
            }
            return

        default:
            if (logEnable) log.debug "0x0300:${descMap.attrId}:${rawValue}"
            return
    }

    sendEventIfChanged(name, value, descriptionText, unit)
    if (name in ["hue", "saturation"]) setGenericColorName()
}

private void parseRadarConfigAttribute(Integer attrInt, Object rawValueObject) {
    Integer raw = hexToInt(rawValueObject, -1)
    if (raw < 0) return

    switch (attrInt) {
        case ATTR_DETECT_DISTANCE:
            updateSettingIfDifferent("detectDistance", raw.toString(), "enum")
            sendEventIfChanged("radarDetectionDistance", raw, null, "m")
            break
        case ATTR_TVOC_THRESHOLD:
            updateSettingIfDifferent("airQualityThreshold", raw, "number")
            sendEventIfChanged("tvocAlertThreshold", raw, null, "ppb")
            break
        case ATTR_MOTION_SENS:
            updateSettingIfDifferent("motionSensitivity", raw, "number")
            sendEventIfChanged("radarMotionSensitivity", raw, null, null)
            break
        case ATTR_PRESENCE_SENS:
            updateSettingIfDifferent("presenceSensitivity", raw, "number")
            sendEventIfChanged("radarPresenceSensitivity", raw, null, null)
            break
        case ATTR_HOLD_TIME:
            updateSettingIfDifferent("presenceHoldTime", raw.toString(), "enum")
            sendEventIfChanged("radarPresenceHoldTime", raw, null, null)
            break
        case ATTR_TVOC_ALERT_ENABLE:
            Boolean enabled = raw != 0
            updateSettingIfDifferent("tvocAlertEnable", enabled, "bool")
            sendEventIfChanged("tvocAlertEnabled", enabled ? "enabled" : "disabled", null, null)
            break
        default:
            if (logEnable) log.debug "Unknown radar/TVOC attribute 0x${zigbee.convertToHexString(attrInt, 4)} value=${rawValueObject}"
            break
    }
}

private void reportRadarWriteStatus(List data) {
    if (!data) return
    if (data.size() == 1 && data[0]?.toString()?.equalsIgnoreCase("00")) {
        if (logEnable) log.debug "Radar/TVOC device setting write accepted"
        return
    }
    for (Integer i = 0; i + 2 < data.size(); i += 3) {
        String status = data[i]?.toString()?.toUpperCase()
        if (status == "00") continue
        String attrId = "${data[i + 2]}${data[i + 1]}".toUpperCase()
        String suffix = status == "86" ? " (attribute not supported by this firmware)" : ""
        log.warn "Radar/TVOC setting write to 0x${attrId} rejected: status 0x${status}${suffix}"
    }
}

private void handleFirmwareVersion(Object rawValue) {
    String version = decodeZigbeeString(rawValue)
    if (!version) return
    device.updateDataValue("firmware", version)
    sendEventIfChanged("firmwareVersion", version, "${device.displayName} firmware version is ${version}", null)
}

private void handleOccupancyReport(Boolean occupied) {
    Integer clearSeconds = Math.max(0, safeToInt(settings.motionClearSeconds, 0))

    if (occupied) {
        sendEventIfChanged("motion", "active", "${device.displayName} motion is active", null)
        sendEventIfChanged("occupancy", "occupied", "${device.displayName} occupancy is occupied", null)
        state.lastOccupiedReportMs = now()
        if (clearSeconds > 0) {
            runIn(clearSeconds, "syntheticMotionClear")
            if (logEnable) log.debug "Scheduled synthetic motion clear in ${clearSeconds} second(s)"
        }
    } else {
        unschedule("syntheticMotionClear")
        sendEventIfChanged("motion", "inactive", "${device.displayName} motion is inactive", null)
        sendEventIfChanged("occupancy", "clear", "${device.displayName} occupancy is clear", null)
    }
}

def syntheticMotionClear() {
    Integer clearSeconds = Math.max(0, safeToInt(settings.motionClearSeconds, 0))
    if (clearSeconds <= 0) return

    Long lastOccupiedMs = state.lastOccupiedReportMs != null ? (state.lastOccupiedReportMs as Long) : 0L
    Long elapsedMs = lastOccupiedMs > 0L ? (now() - lastOccupiedMs) : Long.MAX_VALUE
    Long targetMs = clearSeconds * 1000L

    if (elapsedMs < targetMs) {
        Integer remainingSeconds = Math.max(1, Math.ceil((targetMs - elapsedMs) / 1000.0d) as Integer)
        runIn(remainingSeconds, "syntheticMotionClear")
        return
    }

    String motionCurrent = device.currentValue("motion")?.toString()
    String occupancyCurrent = device.currentValue("occupancy")?.toString()
    if (motionCurrent != "inactive" || occupancyCurrent != "clear") {
        sendEventIfChanged("motion", "inactive", "${device.displayName} motion is inactive", null)
        sendEventIfChanged("occupancy", "clear", "${device.displayName} occupancy is clear", null)
        if (txtEnable) log.info "${device.displayName} motion/presence was cleared by driver timeout (${clearSeconds}s)"
    }
}


private void handleTvocReport(Integer tvoc) {
    Integer minDelta = Math.max(0, safeToInt(settings.tvocMinDelta, 2))
    Integer minSeconds = Math.max(0, safeToInt(settings.tvocMinSeconds, 30))
    Long nowMs = now()
    Integer lastReportedTvoc = state.lastReportedTvoc != null ? safeToInt(state.lastReportedTvoc, tvoc) : null
    Long lastReportMs = state.lastTvocReportMs != null ? (state.lastTvocReportMs as Long) : 0L
    String status = classifyTvoc(tvoc)
    String currentStatus = device.currentValue("AirQuality")?.toString()

    Boolean shouldSendValue = false
    if (lastReportedTvoc == null) {
        shouldSendValue = true
    } else if (currentStatus != status) {
        shouldSendValue = true
    } else {
        Integer delta = Math.abs(tvoc - lastReportedTvoc)
        Long elapsedMs = nowMs - lastReportMs
        if (delta >= minDelta && elapsedMs >= (minSeconds * 1000L)) shouldSendValue = true
    }

    sendEventIfChanged("AirQuality", status, "${device.displayName} Air Quality is ${status}", null)

    if (shouldSendValue) {
        state.lastReportedTvoc = tvoc
        state.lastTvocReportMs = nowMs
        // Retain AirQualityIndex for compatibility, while tvoc is the correctly labeled ppb value.
        sendEventIfChanged("tvoc", tvoc, null, "ppb")
        sendEventIfChanged("AirQualityIndex", tvoc, "${device.displayName} TVOC / Air Quality Index is ${tvoc} ppb (${status})", "ppb")
    } else if (logEnable) {
        log.debug "Filtered TVOC / Air Quality Index report: ${tvoc} (${status})"
    }
}


private void handleIlluminanceReport(Integer lux) {
    Integer minDelta = Math.max(0, safeToInt(settings.illuminanceMinDeltaLux, 3))
    Integer minSeconds = Math.max(0, safeToInt(settings.illuminanceMinSeconds, 30))
    Long nowMs = now()
    Integer lastReportedLux = state.lastReportedIlluminanceLux != null ? safeToInt(state.lastReportedIlluminanceLux, lux) : null
    Long lastReportMs = state.lastIlluminanceReportMs != null ? (state.lastIlluminanceReportMs as Long) : 0L

    Boolean shouldSend = false
    if (lastReportedLux == null) {
        shouldSend = true
    } else {
        Integer delta  = Math.abs(lux - lastReportedLux)
        Long elapsedMs = nowMs - lastReportMs
        if (delta >= minDelta) {
            shouldSend = true
        } else if (lux != lastReportedLux && elapsedMs >= (minSeconds * 1000L)) {
            shouldSend = true
        }
    }

    if (shouldSend) {
        state.lastReportedIlluminanceLux = lux
        state.lastIlluminanceReportMs = nowMs
        sendEventIfChanged("illuminance", lux, "${device.displayName} illuminance is ${lux} Lux", "Lux")
    } else if (logEnable) {
        log.debug "Filtered illuminance report: ${lux} Lux"
    }
}


private String buildTvocDebugLine(Map descMap, BigDecimal tvocValue) {
    String hex       = (descMap?.value ?: "").toString()
    Integer encoding = hexToInt(descMap?.encoding, -1)
    Float fBig       = decodeFloatBigEndian(hex)
    Float fLittle    = decodeFloatLittleEndian(hex)
    Long unsignedVal = null
    Long signedVal   = null
    try {
        unsignedVal = parseUnsignedHex(hex)
    } catch (ignored) { }
    try {
        signedVal = parseSignedHex(hex, Math.max(1, (hex?.length() ?: 0) / 2))
    } catch (ignored) { }

    String status = tvocValue != null ? classifyTvoc(tvocValue.setScale(0, BigDecimal.ROUND_HALF_UP).toInteger()) : null
    return "TVOC/Air Quality Index 0x042E debug: enc=${String.format('0x%02X', encoding)} hex=${hex} unsigned=${unsignedVal} signed=${signedVal} floatBE=${fBig} floatLE=${fLittle} decoded=${tvocValue} status=${status}"
}
private BigDecimal parseTvocValue(Map descMap) {
    Integer encoding = hexToInt(descMap.encoding, -1)
    String hex = descMap.value ?: ""
    if (!hex) return null

    switch (encoding) {
        case 0x39: // single-precision float; legacy/alternate TVOC representation
            Float f1     = decodeFloatBigEndian(hex)
            Float f2     = decodeFloatLittleEndian(hex)
            Float chosen = chooseReasonableFloat(f1, f2)
            if (chosen == null) return null
            return BigDecimal.valueOf(chosen as Double)

        case 0x20: // uint8
        case 0x21: // uint16
        case 0x22: // uint24
        case 0x23: // uint32
            return BigDecimal.valueOf(parseUnsignedHex(hex))

        case 0x28: // int8
        case 0x29: // int16
        case 0x2A: // int24
        case 0x2B: // int32
            return BigDecimal.valueOf(parseSignedHex(hex, hex.length() / 2))

        default:
            // Fall back to unsigned integer interpretation; this matches how many Hubitat descMap values appear.
            try {
                return BigDecimal.valueOf(parseUnsignedHex(hex))
            } catch (ignored) {
                return null
            }
    }
}

private static String classifyTvoc(Integer value) {
    if (value == null) return null
    if (value == 0)  return "possible error"
    if (value <= 500)  return "good"
    if (value <= 1000) return "ventilate"
    if (value <= 3000) return "warning"
    return "danger"
}

private static Float chooseReasonableFloat(Float a, Float b) {
    List<Float> candidates = [a, b].findAll { it != null && !it.isNaN() && !it.isInfinite() && it >= 0.0f && it <= 1000000.0f }
    if (!candidates) return null

    // Prefer a practical non-subnormal value; cluster 0x042E reports on this device are arriving as
    // IEEE-754 values like 41.0 / 42.0 / 60.0, while the opposite endianness decodes to tiny near-zero noise.
    List<Float> practical = candidates.findAll { it >= 0.001f }
    if (practical) return practical.max()

    return candidates.max()
}

private static Float decodeFloatBigEndian(String hex) {
    try {
        int bits = (int) Long.parseLong(hex, 16)
        return Float.intBitsToFloat(bits)
    } catch (ignored) {
        return null
    }
}

private static Float decodeFloatLittleEndian(String hex) {
    try {
        int bits = (int) Long.parseLong(hex, 16)
        return Float.intBitsToFloat(Integer.reverseBytes(bits))
    } catch (ignored) {
        return null
    }
}

private static Long parseUnsignedHex(String hex) {
    return Long.parseLong(hex, 16)
}

private static Long parseSignedHex(String hex, Integer bytes) {
    long unsigned  = Long.parseLong(hex, 16)
    long signBit   = 1L << ((bytes * 8) - 1)
    long fullRange = 1L << (bytes * 8)
    return (unsigned & signBit) ? (unsigned - fullRange) : unsigned
}

private void setGenericColorName() {
    Integer hue = safeToInt(device.currentValue("hue"), 0)
    Integer sat = safeToInt(device.currentValue("saturation"), 100)
    if (!hiRezHue) hue = Math.round(hue * 3.6)
    String colorName

    switch (hue) {
        case 0..15:    colorName = "Red";        break
        case 16..45:   colorName = "Orange";     break
        case 46..75:   colorName = "Yellow";     break
        case 76..105:  colorName = "Chartreuse"; break
        case 106..135: colorName = "Green";      break
        case 136..165: colorName = "Spring";     break
        case 166..195: colorName = "Cyan";       break
        case 196..225: colorName = "Azure";      break
        case 226..255: colorName = "Blue";       break
        case 256..285: colorName = "Violet";     break
        case 286..315: colorName = "Magenta";    break
        case 316..345: colorName = "Rose";       break
        default:       colorName = "Red";        break
    }
    if (sat == 0) colorName = "White"
    sendEventIfChanged("colorName", colorName, "${device.displayName} color is ${colorName}", null)
}

def on() {
    if (logEnable) log.debug "on()"
    Integer transitionMs = getConfiguredTransitionMs("onTransitionTime", 1000)
    Integer targetLevel  = resolveOnLevelPercent()
    Integer zigbeeLevel  = scalePercentToZigbeeLevel(targetLevel)
    Integer transition   = transitionMsToTenths(transitionMs)
    Integer delayMs      = Math.max(400, transitionMs + 400)

    return [
        "he cmd 0x${device.deviceNetworkId} 0x${device.endpointId} 0x0008 4 {0x${intTo8bitUnsignedHex(zigbeeLevel)} 0x${intTo16bitUnsignedHexLE(transition)}}",
        "delay ${delayMs}",
        readAttrCmd(CLUSTER_ON_OFF, 0x0000),
        "delay 200",
        readAttrCmd(CLUSTER_LEVEL,  0x0000)
    ]
}

def off() {
    if (logEnable) log.debug "off()"
    Integer transitionMs = getConfiguredTransitionMs("offTransitionTime", 1000)
    Integer transition = transitionMsToTenths(transitionMs)
    Integer delayMs = Math.max(400, transitionMs + 400)

    return [
        "he cmd 0x${device.deviceNetworkId} 0x${device.endpointId} 0x0008 4 {0x00 0x${intTo16bitUnsignedHexLE(transition)}}",
        "delay ${delayMs}",
        readAttrCmd(CLUSTER_ON_OFF, 0x0000),
        "delay 200",
        readAttrCmd(CLUSTER_LEVEL,  0x0000)
    ]
}

def startLevelChange(direction) {
    if (logEnable) log.debug "startLevelChange(${direction})"
    Integer upDown = direction == "down" ? 1 : 0
    Integer unitsPerSecond = Math.max(1, safeToInt(settings.startLevelChangeRate, 100))
    return "he cmd 0x${device.deviceNetworkId} 0x${device.endpointId} 0x0008 1 {0x${intTo8bitUnsignedHex(upDown)} 0x${intTo8bitUnsignedHex(unitsPerSecond)}}"
}

def stopLevelChange() {
    if (logEnable) log.debug "stopLevelChange()"
    return [
        "he cmd 0x${device.deviceNetworkId} 0x${device.endpointId} 0x0008 3 {}",
        "delay 200",
        readAttrCmd(CLUSTER_LEVEL, 0x0000)
    ]
}

def setLevel(value) {
    if (logEnable) log.debug "setLevel(${value})"
    return setLevel(value, getConfiguredTransitionSeconds("levelTransitionTime", 1.0G))
}

def setLevel(value, rate) {
    if (logEnable) log.debug "setLevel(${value}, ${rate})"
    Integer requestedLevel = Math.max(0, Math.min(100, safeToInt(value, 0)))
    Integer level = clampLevelPercent(requestedLevel)
    BigDecimal defaultRateSeconds = getConfiguredTransitionSeconds("levelTransitionTime", 1.0G)
    BigDecimal rateSeconds = hasMeaningfulValue(rate) ? safeToBigDecimal(rate, defaultRateSeconds) : defaultRateSeconds
    if (rateSeconds <= 0) rateSeconds = defaultRateSeconds > 0 ? defaultRateSeconds : 1.0G
    Integer scaledRate  = Math.max(1, (rateSeconds * 10).toInteger())
    Integer zigbeeLevel = scalePercentToZigbeeLevel(level)
    Boolean isOn    = device.currentValue("switch") == "on"
    Integer delayMs = Math.max(400, (rateSeconds * 1000).toInteger() + 400)

    if (level > 0) state.lastNonZeroLevel = level

    if (isOn) {
        return [
            "he cmd 0x${device.deviceNetworkId} 0x${device.endpointId} 0x0008 4 {0x${intTo8bitUnsignedHex(zigbeeLevel)} 0x${intTo16bitUnsignedHexLE(scaledRate)}}",
            "delay ${delayMs}",
            readAttrCmd(CLUSTER_LEVEL, 0x0000)
        ]
    } else {
        return [
            "he cmd 0x${device.deviceNetworkId} 0x${device.endpointId} 0x0008 4 {0x${intTo8bitUnsignedHex(zigbeeLevel)} 0x${intTo16bitUnsignedHexLE(scaledRate)}}",
            "delay ${delayMs}",
            readAttrCmd(CLUSTER_ON_OFF, 0x0000),
            "delay 200",
            readAttrCmd(CLUSTER_LEVEL, 0x0000)
        ]
    }
}

def setColor(Map value) {
    if (logEnable) log.debug "setColor(${value})"
    if (value?.hue == null || value?.saturation == null) return

    Integer hueInput   = Math.max(0, Math.min(hiRezHue ? 360 : 100, safeToInt(value.hue, 0)))
    Integer satInput   = Math.max(0, Math.min(100, safeToInt(value.saturation, 100)))
    Integer levelInput = value.level != null ? clampLevelPercent(safeToInt(value.level, 100)) : null
    Integer requestedRateSeconds = value.rate != null ? safeToInt(value.rate, 0) : 0
    Integer rateMs = requestedRateSeconds > 0 ? (requestedRateSeconds * 1000) : getConfiguredTransitionMs("rgbTransitionTime", 1000)
    Boolean isOn   = device.currentValue("switch") == "on"

    String hexHue = hiRezHue ?
        zigbee.convertToHexString(Math.round(hueInput / 360.0 * 254).toInteger(), 2) :
        zigbee.convertToHexString(Math.round(hueInput / 100.0 * 254).toInteger(), 2)
    String hexSat = zigbee.convertToHexString(Math.round(satInput / 100.0 * 254).toInteger(), 2)

    List<String> cmds = []
    Integer transition = transitionMsToTenths(rateMs)
    Integer zigbeeLevel = levelInput != null ? scalePercentToZigbeeLevel(levelInput) : null

    if (levelInput != null && levelInput > 0) state.lastNonZeroLevel = levelInput

    if (isOn) {
        cmds << "he cmd 0x${device.deviceNetworkId} 0x${device.endpointId} 0x0300 0x06 {${hexHue} ${hexSat} ${intTo16bitUnsignedHexLE(transition)}}"
        if (zigbeeLevel != null) {
            cmds << "delay 200"
            cmds << "he cmd 0x${device.deviceNetworkId} 0x${device.endpointId} 0x0008 4 {0x${intTo8bitUnsignedHex(zigbeeLevel)} 0x${intTo16bitUnsignedHexLE(transition)}}"
            cmds << "delay ${rateMs + 400}"
            cmds << readAttrCmd(CLUSTER_COLOR, 0x0000)
            cmds << "delay 200"
            cmds << readAttrCmd(CLUSTER_COLOR, 0x0001)
            cmds << "delay 200"
            cmds << readAttrCmd(CLUSTER_LEVEL, 0x0000)
        } else {
            cmds << "delay ${rateMs + 400}"
            cmds << readAttrCmd(CLUSTER_COLOR, 0x0000)
            cmds << "delay 200"
            cmds << readAttrCmd(CLUSTER_COLOR, 0x0001)
        }
    } else if (colorStaging) {
        cmds << "he cmd 0x${device.deviceNetworkId} 0x${device.endpointId} 0x0300 0x06 {${hexHue} ${hexSat} ${intTo16bitUnsignedHexLE(transition)}}"
        cmds << "delay ${Math.max(200, rateMs + 200)}"
        cmds << readAttrCmd(CLUSTER_COLOR,  0x0000)
        cmds << "delay 200"
        cmds << readAttrCmd(CLUSTER_COLOR,  0x0001)
    } else if (zigbeeLevel != null) {
        cmds << "he cmd 0x${device.deviceNetworkId} 0x${device.endpointId} 0x0300 0x06 {${hexHue} ${hexSat} ${intTo16bitUnsignedHexLE(transition)}}"
        cmds << "delay 200"
        cmds << "he cmd 0x${device.deviceNetworkId} 0x${device.endpointId} 0x0008 4 {0x${intTo8bitUnsignedHex(zigbeeLevel)} 0x${intTo16bitUnsignedHexLE(transition)}}"
        cmds << "delay ${rateMs + 400}"
        cmds << readAttrCmd(CLUSTER_ON_OFF, 0x0000)
        cmds << "delay 200"
        cmds << readAttrCmd(CLUSTER_LEVEL,  0x0000)
        cmds << "delay 200"
        cmds << readAttrCmd(CLUSTER_COLOR,  0x0000)
        cmds << "delay 200"
        cmds << readAttrCmd(CLUSTER_COLOR,  0x0001)
    } else {
        cmds << "he cmd 0x${device.deviceNetworkId} 0x${device.endpointId} 0x0300 0x06 {${hexHue} ${hexSat} ${intTo16bitUnsignedHexLE(transition)}}"
        cmds << "delay 200"
        cmds << on()
        cmds = cmds.flatten()
    }

    state.lastHue = hexHue
    state.lastSaturation = hexSat
    sendEventIfChanged("colorMode", "RGB", "${device.displayName} color mode is RGB", null)
    return cmds.flatten()
}

def setHue(value) {
    if (logEnable) log.debug "setHue(${value})"
    Integer maxHue = hiRezHue ? 360 : 100
    Integer hueInput = Math.max(0, Math.min(maxHue, safeToInt(value, 0)))
    Integer rateMs = getConfiguredTransitionMs("rgbTransitionTime", 1000)
    Integer transition = transitionMsToTenths(rateMs)
    String hexHue = hiRezHue ?
        zigbee.convertToHexString(Math.round(hueInput / 360.0d * 254).toInteger(), 2) :
        zigbee.convertToHexString(Math.round(hueInput / 100.0d * 254).toInteger(), 2)

    List cmds = ["he cmd 0x${device.deviceNetworkId} 0x${device.endpointId} 0x0300 0x00 {${hexHue} 00 ${intTo16bitUnsignedHexLE(transition)}}"]
    state.lastHue = hexHue
    sendEventIfChanged("colorMode", "RGB", "${device.displayName} color mode is RGB", null)

    if (device.currentValue("switch") != "on" && !colorStaging) {
        cmds << "delay 200"
        cmds += on()
        cmds << "delay 200"
        cmds << readAttrCmd(CLUSTER_COLOR, 0x0000)
        cmds << "delay 200"
        cmds << readAttrCmd(CLUSTER_COLOR, ATTR_COLOR_MODE)
    } else {
        cmds << "delay ${Math.max(300, rateMs + 300)}"
        cmds << readAttrCmd(CLUSTER_COLOR, 0x0000)
        cmds << "delay 200"
        cmds << readAttrCmd(CLUSTER_COLOR, ATTR_COLOR_MODE)
    }
    return cmds.flatten()
}

def setSaturation(value) {
    if (logEnable) log.debug "setSaturation(${value})"
    Integer satInput = Math.max(0, Math.min(100, safeToInt(value, 100)))
    Integer rateMs = getConfiguredTransitionMs("rgbTransitionTime", 1000)
    Integer transition = transitionMsToTenths(rateMs)
    String hexSat = zigbee.convertToHexString(Math.round(satInput / 100.0d * 254).toInteger(), 2)

    List cmds = ["he cmd 0x${device.deviceNetworkId} 0x${device.endpointId} 0x0300 0x03 {${hexSat} ${intTo16bitUnsignedHexLE(transition)}}"]
    state.lastSaturation = hexSat
    sendEventIfChanged("colorMode", "RGB", "${device.displayName} color mode is RGB", null)

    if (device.currentValue("switch") != "on" && !colorStaging) {
        cmds << "delay 200"
        cmds += on()
        cmds << "delay 200"
        cmds << readAttrCmd(CLUSTER_COLOR, 0x0001)
        cmds << "delay 200"
        cmds << readAttrCmd(CLUSTER_COLOR, ATTR_COLOR_MODE)
    } else {
        cmds << "delay ${Math.max(300, rateMs + 300)}"
        cmds << readAttrCmd(CLUSTER_COLOR, 0x0001)
        cmds << "delay 200"
        cmds << readAttrCmd(CLUSTER_COLOR, ATTR_COLOR_MODE)
    }
    return cmds.flatten()
}

def setColorTemperature(value) {
    return setColorTemperature(value, null, null)
}

def setColorTemperature(value, level) {
    return setColorTemperature(value, level, null)
}

def setColorTemperature(value, level, transitionTime) {
    if (logEnable) log.debug "setColorTemperature(${value}, ${level}, ${transitionTime})"
    Integer kelvin = Math.max(CT_MIN_KELVIN, Math.min(CT_MAX_KELVIN, safeToInt(value, 2700)))
    Integer mireds = kelvinToMireds(kelvin)
    Integer rateMs = hasMeaningfulValue(transitionTime) ?
        Math.max(0, (safeToBigDecimal(transitionTime, 1.0G) * 1000).toInteger()) :
        getConfiguredTransitionMs("colorTemperatureTransitionTime", 1000)
    Integer transition = transitionMsToTenths(rateMs)
    Integer levelInput = hasMeaningfulValue(level) ? clampLevelPercent(safeToInt(level, 100)) : null
    Integer zigbeeLevel = levelInput != null ? scalePercentToZigbeeLevel(levelInput) : null
    Boolean isOn = device.currentValue("switch") == "on"

    List cmds = ["he cmd 0x${device.deviceNetworkId} 0x${device.endpointId} 0x0300 0x0A {${intTo16bitUnsignedHexLE(mireds)} ${intTo16bitUnsignedHexLE(transition)}}"]
    sendEventIfChanged("colorMode", "CT", "${device.displayName} color mode is CT", null)

    if (isOn) {
        if (zigbeeLevel != null) {
            if (levelInput > 0) state.lastNonZeroLevel = levelInput
            cmds << "delay 200"
            cmds << "he cmd 0x${device.deviceNetworkId} 0x${device.endpointId} 0x0008 4 {0x${intTo8bitUnsignedHex(zigbeeLevel)} 0x${intTo16bitUnsignedHexLE(transition)}}"
        }
    } else if (colorStaging) {
        // Stage color temperature while leaving the light off; ignore an optional level just as setColor does.
    } else if (zigbeeLevel != null) {
        if (levelInput > 0) state.lastNonZeroLevel = levelInput
        cmds << "delay 200"
        cmds << "he cmd 0x${device.deviceNetworkId} 0x${device.endpointId} 0x0008 4 {0x${intTo8bitUnsignedHex(zigbeeLevel)} 0x${intTo16bitUnsignedHexLE(transition)}}"
    } else {
        cmds << "delay 200"
        cmds += on()
    }

    cmds << "delay ${Math.max(400, rateMs + 400)}"
    cmds << readAttrCmd(CLUSTER_COLOR, ATTR_COLOR_TEMP_MIREDS)
    cmds << "delay 200"
    cmds << readAttrCmd(CLUSTER_COLOR, ATTR_COLOR_MODE)
    if (zigbeeLevel != null && !colorStaging) {
        cmds << "delay 200"
        cmds << readAttrCmd(CLUSTER_LEVEL, 0x0000)
        cmds << "delay 200"
        cmds << readAttrCmd(CLUSTER_ON_OFF, 0x0000)
    }
    return cmds.flatten()
}

def refresh() {
    if (logEnable) log.debug "refresh()"
    List cmds = [
        readAttrCmd(CLUSTER_BASIC, ATTR_SW_BUILD_ID),       "delay 200",
        readAttrCmd(CLUSTER_ON_OFF, 0x0000),                "delay 200",
        readAttrCmd(CLUSTER_ON_OFF, ATTR_POWER_ON_BEHAVIOR),"delay 200",
        readAttrCmd(CLUSTER_LEVEL, 0x0000),                 "delay 200",
        readAttrCmd(CLUSTER_LEVEL, ATTR_ONOFF_TRANSITION),  "delay 200",
        readAttrCmd(CLUSTER_LEVEL, ATTR_ON_LEVEL),          "delay 200",
        readAttrCmd(CLUSTER_LEVEL, ATTR_STARTUP_LEVEL),     "delay 200",
        readAttrCmd(CLUSTER_COLOR, 0x0000),                 "delay 200",
        readAttrCmd(CLUSTER_COLOR, 0x0001),                 "delay 200",
        readAttrCmd(CLUSTER_COLOR, ATTR_COLOR_TEMP_MIREDS), "delay 200",
        readAttrCmd(CLUSTER_COLOR, ATTR_COLOR_MODE),        "delay 200",
        readAttrCmd(CLUSTER_COLOR, ATTR_ENHANCED_MODE),     "delay 200",
        readAttrCmd(CLUSTER_COLOR, ATTR_STARTUP_CT_MIREDS), "delay 200",
        readAttrCmd(CLUSTER_ILLUMINANCE, 0x0000),           "delay 200",
        readAttrCmd(CLUSTER_OCCUPANCY, 0x0000),             "delay 200"
    ]
    cmds += radarReadCommands()
    return cmds.flatten()
}

def configure() {
    log.warn "configure..."
    publishDriverVersion()
    if (logEnable) runIn(1800, "logsOff")

    List cmds = [
        "zdo bind 0x${device.deviceNetworkId} 0x${device.endpointId} 0x01 0x0006 {${device.zigbeeId}} {}", "delay 200",
        "zdo bind 0x${device.deviceNetworkId} 0x${device.endpointId} 0x01 0x0008 {${device.zigbeeId}} {}", "delay 200",
        "zdo bind 0x${device.deviceNetworkId} 0x${device.endpointId} 0x01 0x0300 {${device.zigbeeId}} {}", "delay 200",
        "zdo bind 0x${device.deviceNetworkId} 0x${device.endpointId} 0x01 0x0400 {${device.zigbeeId}} {}", "delay 200",
        "zdo bind 0x${device.deviceNetworkId} 0x${device.endpointId} 0x01 0x0406 {${device.zigbeeId}} {}", "delay 200",
        "zdo bind 0x${device.deviceNetworkId} 0x${device.endpointId} 0x01 0x042E {${device.zigbeeId}} {}", "delay 200"
    ]

    cmds += zigbee.configureReporting(CLUSTER_ON_OFF,      0x0000, 0x10, 0,  3600, null)
    cmds += zigbee.configureReporting(CLUSTER_LEVEL,       0x0000, 0x20, 1,  3600, 1)
    cmds += zigbee.configureReporting(CLUSTER_COLOR,       0x0000, 0x20, 1,  3600, 1)
    cmds += zigbee.configureReporting(CLUSTER_COLOR,       0x0001, 0x20, 1,  3600, 1)
    cmds += zigbee.configureReporting(CLUSTER_COLOR,       ATTR_COLOR_TEMP_MIREDS, 0x21, 1, 3600, 1)
    cmds += zigbee.configureReporting(CLUSTER_COLOR,       ATTR_COLOR_MODE, 0x30, 0, 3600)
    cmds += zigbee.configureReporting(CLUSTER_ILLUMINANCE, 0x0000, 0x21, 30, 300, 50)
    cmds += zigbee.configureReporting(CLUSTER_OCCUPANCY,   0x0000, 0x18, 0, 3600, 1)
    cmds += refresh()
    return cmds.flatten()
}

def applyDeviceSettings() {
    log.warn "Applying explicitly selected radar, TVOC, and light start-up settings to the device..."
    List cmds = buildDeviceSettingWrites()
    if (!cmds) {
        log.warn "No device settings are populated; refresh the device or select preferences before applying."
        return []
    }
    cmds << "delay 1200"
    cmds += deviceSettingReadCommands()
    return cmds.flatten()
}

def resetTVOCCalibration() {
    log.warn "Resetting the TVOC calibration baseline..."
    List cmds = []
    cmds += zigbee.writeAttribute(CLUSTER_TVOC, ATTR_TVOC_CALIBRATE, 0x20, 1, [mfgCode: MFG_CODE])
    cmds << "delay 1500"
    cmds += zigbee.readAttribute(CLUSTER_TVOC, ATTR_TVOC, [mfgCode: MFG_CODE])
    return cmds.flatten()
}

def updateFirmware() {
    log.warn "Requesting a Zigbee OTA firmware check/update..."
    return zigbee.updateFirmware()
}

private List buildDeviceSettingWrites() {
    List cmds = []

    Integer dist = nullableInt(settings.detectDistance)
    if (dist != null) cmds += zigbee.writeAttribute(CLUSTER_TVOC, ATTR_DETECT_DISTANCE, 0x20, Math.max(1, Math.min(6, dist)), [mfgCode: MFG_CODE])

    Integer motionSens = nullableInt(settings.motionSensitivity)
    if (motionSens != null) cmds += zigbee.writeAttribute(CLUSTER_TVOC, ATTR_MOTION_SENS, 0x20, Math.max(0, Math.min(20, motionSens)), [mfgCode: MFG_CODE])

    Integer presenceSens = nullableInt(settings.presenceSensitivity)
    if (presenceSens != null) cmds += zigbee.writeAttribute(CLUSTER_TVOC, ATTR_PRESENCE_SENS, 0x20, Math.max(0, Math.min(20, presenceSens)), [mfgCode: MFG_CODE])

    Integer hold = nullableInt(settings.presenceHoldTime)
    if (hold != null) cmds += zigbee.writeAttribute(CLUSTER_TVOC, ATTR_HOLD_TIME, 0x20, Math.max(1, Math.min(4, hold)), [mfgCode: MFG_CODE])

    Integer threshold = nullableInt(settings.airQualityThreshold)
    if (threshold != null) cmds += zigbee.writeAttribute(CLUSTER_TVOC, ATTR_TVOC_THRESHOLD, 0x21, Math.max(3000, Math.min(50000, threshold)), [mfgCode: MFG_CODE])

    if (settings.tvocAlertEnable != null) {
        cmds += zigbee.writeAttribute(CLUSTER_TVOC, ATTR_TVOC_ALERT_ENABLE, 0x20, settings.tvocAlertEnable ? 1 : 0, [mfgCode: MFG_CODE])
    }

    Integer powerBehavior = nullableInt(settings.powerOnBehavior)
    if (powerBehavior != null) cmds += zigbee.writeAttribute(CLUSTER_ON_OFF, ATTR_POWER_ON_BEHAVIOR, 0x30, powerBehavior)

    Integer ordinaryOnLevel = nullableInt(settings.onLevel)
    if (ordinaryOnLevel != null) {
        Integer raw = Math.max(1, Math.min(254, Math.round(Math.max(1, Math.min(100, ordinaryOnLevel)) * 254 / 100.0d) as Integer))
        cmds += zigbee.writeAttribute(CLUSTER_LEVEL, ATTR_ON_LEVEL, 0x20, raw)
    }

    Integer startupLevel = nullableInt(settings.startUpLevel)
    if (startupLevel != null) {
        Integer pct = Math.max(0, Math.min(100, startupLevel))
        Integer raw = pct == 0 ? 0 : Math.max(1, Math.min(254, Math.round(pct * 254 / 100.0d) as Integer))
        cmds += zigbee.writeAttribute(CLUSTER_LEVEL, ATTR_STARTUP_LEVEL, 0x20, raw)
    }

    BigDecimal defaultTransition = nullableBigDecimal(settings.deviceDefaultTransitionSeconds)
    if (defaultTransition != null) {
        Integer tenths = Math.max(0, Math.min(300, Math.round(defaultTransition.doubleValue() * 10.0d) as Integer))
        cmds += zigbee.writeAttribute(CLUSTER_LEVEL, ATTR_ONOFF_TRANSITION, 0x21, tenths)
    }

    Integer startupCt = nullableInt(settings.startUpColorTempK)
    if (startupCt != null) {
        Integer kelvin = Math.max(CT_MIN_KELVIN, Math.min(CT_MAX_KELVIN, startupCt))
        cmds += zigbee.writeAttribute(CLUSTER_COLOR, ATTR_STARTUP_CT_MIREDS, 0x21, kelvinToMireds(kelvin))
    }

    return cmds.flatten()
}

private List radarReadCommands() {
    return zigbee.readAttribute(CLUSTER_TVOC,
        [ATTR_TVOC, ATTR_DETECT_DISTANCE, ATTR_TVOC_THRESHOLD, ATTR_MOTION_SENS,
         ATTR_PRESENCE_SENS, ATTR_HOLD_TIME, ATTR_TVOC_ALERT_ENABLE],
        [mfgCode: MFG_CODE])
}

private List deviceSettingReadCommands() {
    List cmds = [
        readAttrCmd(CLUSTER_ON_OFF, ATTR_POWER_ON_BEHAVIOR), "delay 200",
        readAttrCmd(CLUSTER_LEVEL, ATTR_ONOFF_TRANSITION),   "delay 200",
        readAttrCmd(CLUSTER_LEVEL, ATTR_ON_LEVEL),           "delay 200",
        readAttrCmd(CLUSTER_LEVEL, ATTR_STARTUP_LEVEL),      "delay 200",
        readAttrCmd(CLUSTER_COLOR, ATTR_STARTUP_CT_MIREDS),  "delay 200"
    ]
    cmds += radarReadCommands()
    return cmds.flatten()
}

private Integer getConfiguredTransitionMs(String settingName, Integer defaultMs = 1000) {
    Object configured = settings?.get(settingName)
    Integer ms = safeToInt(configured, defaultMs)
    return ms > 0 ? ms : defaultMs
}

private BigDecimal getConfiguredTransitionSeconds(String settingName, BigDecimal defaultSeconds = 1.0G) {
    Integer ms = getConfiguredTransitionMs(settingName, (defaultSeconds * 1000).toInteger())
    return BigDecimal.valueOf(ms / 1000.0d)
}

private Integer transitionMsToTenths(Integer ms) {
    Integer safeMs = Math.max(0, ms ?: 0)
    return Math.max(0, (int) Math.round(safeMs / 100.0d))
}

private Integer getMinimumLevelPercent() {
    Integer minLevel = safeToInt(settings.minimumLevel, 5)
    return Math.max(0, Math.min(100, minLevel))
}

private Integer clampLevelPercent(Integer level) {
    Integer safeLevel = Math.max(0, Math.min(100, level ?: 0))
    if (safeLevel == 0) return 0
    Integer minLevel = getMinimumLevelPercent()
    return Math.max(minLevel, safeLevel)
}

private Integer resolveOnLevelPercent() {
    Integer currentLevel = safeToInt(device.currentValue("level"), 0)
    Integer rememberedLevel = safeToInt(state.lastNonZeroLevel, 100)
    Integer candidate = currentLevel > 0 ? currentLevel : (rememberedLevel > 0 ? rememberedLevel : 100)
    return clampLevelPercent(candidate)
}

private Integer scalePercentToZigbeeLevel(Integer levelPercent) {
    Integer safeLevel = Math.max(0, Math.min(100, levelPercent ?: 0))
    return Math.round(safeLevel * 254 / 100.0d)
}

private Boolean hasMeaningfulValue(Object value) {
    if (value == null) return false
    if (value instanceof String) return value.toString().trim() != ""
    return true
}

private void setColorTemperatureName(Integer kelvin) {
    String colorName = kelvin < 2700 ? "Soft White" :
        kelvin < 3500 ? "Warm White" :
        kelvin < 4500 ? "Neutral White" :
        kelvin < 5500 ? "Cool White" : "Daylight"
    sendEventIfChanged("colorName", colorName, "${device.displayName} color is ${colorName}", null)
}

private static Integer kelvinToMireds(Integer kelvin) {
    Integer safeKelvin = Math.max(CT_MIN_KELVIN, Math.min(CT_MAX_KELVIN, kelvin ?: CT_MIN_KELVIN))
    Integer mireds = Math.round(1000000.0d / safeKelvin)
    return Math.max(CT_MIN_MIREDS, Math.min(CT_MAX_MIREDS, mireds))
}

private static Integer miredsToKelvin(Integer mireds) {
    if (mireds == null || mireds <= 0) return CT_MAX_KELVIN
    return Math.max(CT_MIN_KELVIN, Math.min(CT_MAX_KELVIN, Math.round(1000000.0d / mireds)))
}

private Integer getClusterInt(Map descMap) {
    if (descMap?.clusterInt != null) return safeToInt(descMap.clusterInt, -1)
    Object raw = descMap?.cluster ?: descMap?.clusterId
    Integer parsed = hexToInt(raw, -1)
    return parsed >= 0 ? parsed : null
}

private Integer getAttrInt(Map descMap) {
    if (descMap?.attrInt != null) return safeToInt(descMap.attrInt, -1)
    Integer parsed = hexToInt(descMap?.attrId, -1)
    return parsed >= 0 ? parsed : null
}

private void updateSettingIfDifferent(String settingName, Object value, String type) {
    if (value == null) return
    Object current = settings?.get(settingName)
    if (current != null && current.toString() == value.toString()) return
    device.updateSetting(settingName, [value: value, type: type])
}

private static Integer nullableInt(Object value) {
    if (value == null) return null
    String s = value.toString().trim()
    if (!s || s.equalsIgnoreCase("null")) return null
    try {
        return value instanceof Number ? ((Number) value).intValue() : new BigDecimal(s).intValue()
    } catch (ignored) {
        return null
    }
}

private static BigDecimal nullableBigDecimal(Object value) {
    if (value == null) return null
    String s = value.toString().trim()
    if (!s || s.equalsIgnoreCase("null")) return null
    try {
        return value instanceof BigDecimal ? (BigDecimal) value : new BigDecimal(s)
    } catch (ignored) {
        return null
    }
}

private static String decodeZigbeeString(Object rawValue) {
    if (rawValue == null) return null
    String raw = rawValue.toString().trim()
    if (!raw) return null
    if (!(raw ==~ /(?i)[0-9a-f]+/) || (raw.length() % 2 != 0)) return raw

    try {
        List<Integer> bytes = []
        for (Integer i = 0; i < raw.length(); i += 2) bytes << Integer.parseInt(raw.substring(i, i + 2), 16)
        if (bytes && bytes[0] == bytes.size() - 1) bytes = bytes.drop(1)
        byte[] byteArray = new byte[bytes.size()]
        bytes.eachWithIndex { Integer b, Integer index -> byteArray[index] = (byte) (b & 0xFF) }
        String decoded = new String(byteArray, "UTF-8")
        decoded = decoded.replace("\u0000", "").trim()
        return decoded ?: raw
    } catch (ignored) {
        return raw
    }
}

private String readAttrCmd(Integer cluster, Integer attrId) {
    return "he rattr 0x${device.deviceNetworkId} 0x${device.endpointId} 0x${zigbee.convertToHexString(cluster, 4)} 0x${zigbee.convertToHexString(attrId, 4)} {}"
}

private Boolean sendEventIfChanged(String name, Object value, String descriptionText = null, String unit = null) {
    if (name == null) return false
    String current = device.currentValue(name)?.toString()
    String incoming = value?.toString()
    if (current == incoming) return false

    Map evt = [name: name, value: value]
    if (descriptionText != null) evt.descriptionText = descriptionText
    if (unit != null) evt.unit = unit
    sendEvent(evt)
    if (txtEnable && descriptionText) log.info descriptionText
    return true
}

private static Integer hexToInt(Object value, Integer defaultValue = 0) {
    try {
        if (value == null) return defaultValue
        if (value instanceof Number) return ((Number) value).intValue()
        String s = value.toString().trim()
        if (!s) return defaultValue
        if (s.startsWith("0x") || s.startsWith("0X")) s = s.substring(2)
        return Integer.parseInt(s, 16)
    } catch (ignored) {
        return defaultValue
    }
}

private static Integer safeToInt(Object value, Integer defaultValue = 0) {
    try {
        if (value == null) return defaultValue
        if (value instanceof Number) return ((Number) value).intValue()
        String s = value.toString().trim()
        if (!s) return defaultValue
        if (s.startsWith("0x") || s.startsWith("0X")) return Integer.parseInt(s.substring(2), 16)
        if (s ==~ /[0-9A-Fa-f]+/ && s.length() > 1 && !(s ==~ /\d+/)) return Integer.parseInt(s, 16)
        return Integer.parseInt(s)
    } catch (ignored) {
        return defaultValue
    }
}

private static BigDecimal safeToBigDecimal(Object value, BigDecimal defaultValue = 0G) {
    try {
        if (value == null) return defaultValue
        if (value instanceof BigDecimal) return (BigDecimal) value
        if (value instanceof Number) return new BigDecimal(value.toString())
        String s = value.toString().trim()
        if (!s) return defaultValue
        return new BigDecimal(s)
    } catch (ignored) {
        return defaultValue
    }
}

private String intTo8bitUnsignedHex(Object value) {
    return zigbee.convertToHexString(safeToInt(value, 0) & 0xFF, 2)
}

private String intTo16bitUnsignedHex(Object value) {
    return zigbee.convertToHexString(safeToInt(value, 0) & 0xFFFF, 4)
}

private String intTo16bitUnsignedHexLE(Object value) {
    String hex = intTo16bitUnsignedHex(value)
    return hex.substring(2, 4) + hex.substring(0, 2)
}
