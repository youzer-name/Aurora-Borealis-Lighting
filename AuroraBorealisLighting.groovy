/**
 *  Aurora Borealis Lighting
 *
 *  Copyright 2025
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 */

import groovy.json.JsonSlurper

definition(
    name: "Aurora Borealis Lighting",
    namespace: "custom",
    author: "Colin Ho",
    description: "Simulates Northern Lights by cycling colors on selected color bulbs.",
    category: "Lighting",
    iconUrl: "",
    iconX2Url: "",
    iconX3Url: "")

preferences {
    section("Logging") {
        input "enableDebugLogging", "bool", title: "Enable debug logging?", defaultValue: false, required: false
    }
    section("Select color bulbs for aurora effect") {
        input "colorBulbs", "capability.colorControl", title: "Color Bulbs", multiple: true, required: true
    }
    section("") {
        input "bulbInterval", "number", title: "Set a bulb every (seconds)", required: true, defaultValue: 3
    }
    section("") {
        input "cyclePause", "number", title: "Then pause (seconds)", required: true, defaultValue: 3
    }
    section("") {
        input "auroraLevel", "number", title: "Aurora Effect Brightness", required: true, defaultValue: 100, range: "1..100"
    }
    section("Start/Stop Control (Switch)") {
        input "controlSwitch", "capability.switch", title: "Switch to Start/Stop Effect (optional)", required: false, multiple: false
    }
    section("Start Control (Button)") {
        input "startButtonDevice", "capability.pushableButton", title: "Button Device to Start Effect (optional)", required: false, multiple: false
        input "startButtonNumber", "number", title: "Button Number", required: false, defaultValue: 1
        input "startButtonAction", "enum", title: "Button Action", options: ["pushed", "held", "doubleTapped"], required: false, defaultValue: "pushed"
    }
    section("Stop Control (Button)") {
        input "stopButtonDevice", "capability.pushableButton", title: "Button Device to Stop Effect (optional)", required: false, multiple: false
        input "stopButtonNumber", "number", title: "Button Number", required: false, defaultValue: 1
        input "stopButtonAction", "enum", title: "Button Action", options: ["pushed", "held", "doubleTapped"], required: false, defaultValue: "pushed"
    }
}

def installed() {
    initialize()
}

def updated() {
    unschedule()
    unsubscribe()
    initialize()
}

def uninstalled() {
    unschedule()
    unsubscribe()
}

def initialize() {
    // Subscribe to switch and button events if selected
    if (controlSwitch) {
        subscribe(controlSwitch, "switch.on", onSwitchOn)
        subscribe(controlSwitch, "switch.off", onSwitchOff)
    }
    if (startButtonDevice && startButtonAction && startButtonNumber) {
        subscribe(startButtonDevice, startButtonAction, onButtonEvent)
    }
    if (stopButtonDevice && stopButtonAction && stopButtonNumber) {
        subscribe(stopButtonDevice, stopButtonAction, onStopButtonEvent)
    }

    // Subscribe to bulb state changes for interruption
    if (colorBulbs) {
        colorBulbs.each { bulb ->
            subscribe(bulb, "switch", onBulbStateChange)
            subscribe(bulb, "color", onBulbStateChange)
            subscribe(bulb, "colorTemperature", onBulbStateChange)
        }
    }
    // Do not start/stop effect based on current switch state at initialization
    state.auroraActive = false
    state.auroraFirstCyclePrime = true
}

// Shared helper for button event handling (start/stop)
void handleButtonEvent(evt, actionKey, numberKey, isStart) {
    def action = settings["${actionKey}"] ?: "pushed"
    def btnNum = (settings["${numberKey}"] ?: 1) as Integer
    debugLog "Button event received: name=${evt.name}, value=${evt.value}, data=${evt.data}, device=${evt.device}, action=${action}, btnNum=${btnNum}"
    def eventAction = evt.name
    def eventBtnNum = null
    try {
        if (evt.value) {
            eventBtnNum = evt.value as Integer
            debugLog "Parsed buttonNumber from evt.value: ${eventBtnNum}"
        } else if (evt.data) {
            def json = groovy.json.JsonSlurper.newInstance().parseText(evt.data)
            eventBtnNum = json.buttonNumber as Integer
            debugLog "Parsed buttonNumber from evt.data: ${eventBtnNum}"
        } else {
            eventBtnNum = 1 // fallback for single-button
            debugLog "Fallback to buttonNumber 1"
        }
    } catch (e) {
        eventBtnNum = 1
        debugLog "Exception parsing button number: ${e}"
    }
    if (settings.enableDebugLogging) log.debug "Comparing eventAction=${eventAction} to action=${action}, eventBtnNum=${eventBtnNum} to btnNum=${btnNum}"
    if (eventAction == action && eventBtnNum == btnNum) {
        if (isStart) {
            debugLog "Button event matched, starting animation."
            if (!state.auroraActive) {
                state.auroraActive = true
                state.auroraStarted = false
                state.auroraRestoreOnStop = false
                runIn(2, auroraLoop)
            }
        } else {
            debugLog "Stop button event matched, stopping animation."
            if (state.auroraActive) {
                stopAll()
            }
        }
    }
}


// Handler to interrupt animation if a bulb is changed externally
def onBulbStateChange(evt) {
    def suppressMap = state.auroraSuppressEventsUntil ?: [:]
    def bulbId = evt.device?.id
    if (bulbId && suppressMap[bulbId] && now() < suppressMap[bulbId]) {
        debugLog "Suppressed bulb event for ${evt.device.displayName} ${evt.name}: ${evt.value} (within suppression window for this bulb)"
        return
    }
    // Only track last commanded hue per bulb
    def lastHueMap = state.auroraLastHue ?: [:]
    // If bulb is turned off, abort
    if (evt.name == 'switch' && evt.value == 'off') {
        debugLog "Bulb turned off externally (${evt.device.displayName}), aborting."
        stopAll()
        return
    }
    // Do NOT abort on 'switch on' events
    if (evt.name == 'switch' && evt.value == 'on') {
        debugLog "Ignoring 'switch on' event for ${evt.device.displayName}"
        return
    }
    // If bulb is set to color temperature mode, abort
    if (evt.name == 'colorMode' && evt.value == 'CT') {
        debugLog "Bulb set to CT mode externally (${evt.device.displayName}), aborting."
        stopAll()
        return
    }
    // If hue changes significantly, abort
    if (evt.name == 'hue') {
        def lastHue = lastHueMap[bulbId]
        def newHue = evt.value as Double
        if (lastHue != null && Math.abs(newHue - lastHue) > 5) {
            debugLog "Bulb hue changed externally (${evt.device.displayName} hue: ${evt.value}), aborting."
            stopAll()
            return
        }
    }
    if (state.auroraSuppressAbort) {
        debugLog "Suppressing abort during first cycle for ${evt.device.displayName} ${evt.name}: ${evt.value}"
        return
    }
    // Ignore all other events
    debugLog "Ignoring non-abort event for ${evt.device.displayName} ${evt.name}: ${evt.value}"
    return
}

def auroraLoop() {

    debugLog "auroraLoop called: colorBulbs=${colorBulbs}, state.auroraActive=${state.auroraActive}"
    if (!colorBulbs) { debugLog "No color bulbs selected"; return }
    if (!state.auroraActive) { debugLog "Aurora not active"; return }


    // Only capture bulb states at the very start of the animation, and only if restore is enabled
    if (!state.auroraStarted) {
        if (state.auroraRestoreOnStop) {
            debugLog "Capturing bulb states at animation start"
            captureBulbStates()
        }
        state.auroraStarted = true
        state.auroraSuppressAbort = true // suppress abort until first cycle completes
    }

    def bulbIntervalMs = ((settings.bulbInterval ?: 3) * 1000).toInteger()
    def cyclePauseSec = settings.cyclePause ?: 3
    def bulbs = colorBulbs
    // Turn off all color bulbs NOT in the selected list
    def allColorBulbs = location.allDevices.findAll { it.hasCapability("ColorControl") }
    def unselectedBulbs = allColorBulbs - bulbs
    unselectedBulbs.each { it.off() }
    // Prepare animation step state
    if (!state.auroraStep) state.auroraStep = 0
    def step = state.auroraStep as Integer
    def randomOrder
    if (!state.auroraOrder) {
        randomOrder = bulbs.sort{new Random().nextInt()}
        state.auroraOrder = randomOrder.collect{ it.id }
    } else {
        randomOrder = state.auroraOrder.collect { id -> bulbs.find{ it.id == id } }.findAll{ it }
    }
    // At the start of each cycle, set base hue/sat if not already set
    if (state.auroraBaseHue == null) {
        state.auroraBaseHue = 50 + new Random().nextInt(40)
    }
    if (state.auroraBaseSat == null) {
        state.auroraBaseSat = 85 + new Random().nextInt(15)
    }
    if (step == 0) {
        def baseHue = state.auroraBaseHue
        def baseSaturation = state.auroraBaseSat
        def rawLevel = settings.auroraLevel ?: 100
        def level = Math.max(1, Math.min(100, rawLevel as Integer))
        // Only prime all bulbs on the very first cycle
        if (state.auroraStarted && state.auroraFirstCyclePrime != false) {
            bulbs.each { bulb ->
                debugLog "Priming ${bulb.displayName} to base hue:${baseHue} sat:${baseSaturation} level:${level}"
                try {
                    suppressEventsFor(bulb)
                    // Track last commanded hue
                    setLastHue(bulb, baseHue as Double)
                    bulb.setColor([hue: baseHue, saturation: baseSaturation, level: level])
                } catch (e) {
                    debugLog "setColor failed for ${bulb.displayName}: ${e}"
                }
            }
            state.auroraFirstCyclePrime = false
        }
    }
    // Only animate one bulb per step
    if (step < randomOrder.size()) {
        def bulb = randomOrder[step]
        // Create harmonious base hue for this cycle (aurora colors: green-blue range)
        def baseHue = state.auroraBaseHue
        def baseSaturation = state.auroraBaseSat
        def hueOffset = (step % 3 - 1) * (5 + new Random().nextInt(5))
        def harmonicHue = (baseHue + hueOffset) % 100
        def harmonicSaturation = baseSaturation - new Random().nextInt(10)
        def rawLevel = settings.auroraLevel ?: 100
        def level = Math.max(1, Math.min(100, rawLevel as Integer))
        debugLog "Setting ${bulb.displayName} to hue:${harmonicHue} sat:${harmonicSaturation} level:${level}"
        try {
            suppressEventsFor(bulb)
            // Track last commanded hue
            setLastHue(bulb, harmonicHue as Double)
            bulb.setColor([hue: harmonicHue, saturation: harmonicSaturation, level: level])
        } catch (e) {
            debugLog "setColor failed for ${bulb.displayName}: ${e}"
        }
        state.auroraStep = step + 1
        runInMillis(bulbIntervalMs, auroraLoop)
    } else {
        // Reset for next cycle
        if (state.auroraSuppressAbort) {
            // Only after first cycle: schedule suppression clear after pause
            runIn(Math.round(cyclePauseSec) as int, clearAuroraSuppressAbort)
        }
    resetCycleState()
    runIn(Math.round(cyclePauseSec) as int, auroraLoop)
    }
}

// Helper to clear abort suppression after pause
def clearAuroraSuppressAbort() {
    state.auroraSuppressAbort = false
    debugLog "Abort suppression cleared after first cycle pause."
}

// Capture the state of all selected bulbs before starting the effect
def captureBulbStates() {
    state.savedBulbStates = [:]
    colorBulbs.each { bulb ->
        try {
            def bulbState = [
                switch: bulb.currentSwitch,
                hue: bulb.currentHue,
                saturation: bulb.currentSaturation,
                level: bulb.currentLevel,
                colorMode: bulb.currentColorMode
            ]
            state.savedBulbStates[bulb.id] = bulbState
        } catch (e) {
            debugLog "Could not capture state for ${bulb.displayName}: ${e}"
        }
    }
}

// Restore the state of all bulbs after the effect ends
def restoreBulbStates() {
    if (!state.savedBulbStates) return
    colorBulbs.each { bulb ->
        def prev = state.savedBulbStates[bulb.id]
        if (prev) {
            try {
                if (prev.switch == "off") {
                    suppressEventsFor(bulb)
                    // Remove last commanded hue for this bulb
                    clearLastHue(bulb)
                    bulb.off()
                } else {
                    debugLog "Restoring ${bulb.displayName} to hue:${prev.hue} sat:${prev.saturation} level:${prev.level} mode:${prev.colorMode}"
                    if (prev.colorMode == "CT" && bulb.hasCommand("setColorTemperature")) {
                        // If previous mode was CT, restore with setColorTemperature and level
                        def ct = bulb.currentColorTemperature ?: 4000
                        suppressEventsFor(bulb)
                        // Remove last commanded hue for this bulb
                        clearLastHue(bulb)
                        bulb.setColorTemperature(ct)
                        bulb.setLevel(prev.level)
                    } else if (prev.colorMode == "RGB" || prev.colorMode == "CT") {
                        suppressEventsFor(bulb)
                        // Track last commanded hue for this bulb
                        setLastHue(bulb, prev.hue as Double)
                        bulb.setColor([hue: prev.hue, saturation: prev.saturation, level: prev.level])
                    } else {
                        suppressEventsFor(bulb)
                        // Remove last commanded hue for this bulb
                        clearLastHue(bulb)
                        bulb.setLevel(prev.level)
                    }
                }
            } catch (e) {
                debugLog "Could not restore state for ${bulb.displayName}: ${e}"
            }
        }
    }
    state.savedBulbStates = null
}

// Switch event handlers
def onSwitchOn(evt) {
    if (!state.auroraActive) {
        initialize()
        state.auroraActive = true
        state.auroraStarted = false
        state.auroraRestoreOnStop = true
        runIn(2, auroraLoop)
    }
}
def onSwitchOff(evt) {
    stopAll()
}

private void suppressEventsFor(bulb, long ms = 1000) {
    def map = state.auroraSuppressEventsUntil ?: [:]
    map[bulb.id] = now() + ms
    state.auroraSuppressEventsUntil = map
}

private void setLastHue(bulb, double hue) {
    def map = state.auroraLastHue ?: [:]
    map[bulb.id] = hue
    state.auroraLastHue = map
}

private void clearLastHue(bulb) {
    def map = state.auroraLastHue ?: [:]   // grab the map (or an empty one)
    map.remove(bulb.id)                    // drop the entry for this bulb
    state.auroraLastHue = map              // write it back
}

private void debugLog(String msg) {
    if (settings.enableDebugLogging) log.debug(msg)
}

private void resetCycleState() {
    state.auroraStep = 0
    state.auroraOrder = null
    state.auroraBaseHue = null
    state.auroraBaseSat = null
}

// Button event handler (start only, with button number check)
def onButtonEvent(evt) {
    handleButtonEvent(evt, 'startButtonAction', 'startButtonNumber', true)
}

def onStopButtonEvent(evt) {
    handleButtonEvent(evt, 'stopButtonAction', 'stopButtonNumber', false)
}

// Unsubscribe only from bulb event subscriptions, not triggers
private void unsubscribeBulbEvents() {
    if (colorBulbs) {
        colorBulbs.each { bulb ->
            unsubscribe(bulb, "switch")
            unsubscribe(bulb, "color")
            unsubscribe(bulb, "colorTemperature")
        }
    }
}

private void stopAll() {
    if (state.auroraActive) {
        state.auroraActive = false
        state.auroraStarted = false
        if (state.auroraRestoreOnStop) restoreBulbStates()
        unsubscribeBulbEvents()
    }
}
