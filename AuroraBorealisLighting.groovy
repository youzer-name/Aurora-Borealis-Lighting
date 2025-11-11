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
}

def installed() {
    initialize()
}

def updated() {
    unschedule()
    unsubscribe()
    initialize()
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
    // Subscribe to bulb state changes for interruption
    if (colorBulbs) {
        colorBulbs.each { bulb ->
            subscribe(bulb, "switch", onBulbStateChange)
            subscribe(bulb, "color", onBulbStateChange)
            subscribe(bulb, "level", onBulbStateChange)
            subscribe(bulb, "colorTemperature", onBulbStateChange)
}
    }
    // Do not start/stop effect based on current switch state at initialization
    state.auroraActive = false
        state.auroraFirstCyclePrime = true
}


// Handler to interrupt animation if a bulb is changed externally
def onBulbStateChange(evt) {
    def suppressMap = state.auroraSuppressEventsUntil ?: [:]
    def bulbId = evt.device?.id
    if (bulbId && suppressMap[bulbId] && now() < suppressMap[bulbId]) {
        if (settings.enableDebugLogging) log.debug "Suppressed bulb event for ${evt.device.displayName} ${evt.name}: ${evt.value} (within suppression window for this bulb)"
        return
    }
    // Only track last commanded hue per bulb
    def lastHueMap = state.auroraLastHue ?: [:]
    // If bulb is turned off, abort
    if (evt.name == 'switch' && evt.value == 'off') {
        if (settings.enableDebugLogging) log.info "Bulb turned off externally (${evt.device.displayName}), aborting."
        if (state.auroraActive) {
            state.auroraActive = false
            state.auroraStarted = false
            restoreBulbStates()
        }
        return
    }
    // Do NOT abort on 'switch on' events
    if (evt.name == 'switch' && evt.value == 'on') {
        if (settings.enableDebugLogging) log.debug "Ignoring 'switch on' event for ${evt.device.displayName}"
        return
    }
    // If bulb is set to color temperature mode, abort
    if (evt.name == 'colorMode' && evt.value == 'CT') {
        if (settings.enableDebugLogging) log.info "Bulb set to CT mode externally (${evt.device.displayName}), aborting."
        if (state.auroraActive) {
            state.auroraActive = false
            state.auroraStarted = false
            restoreBulbStates()
        }
        return
    }
    // If hue changes significantly, abort
    if (evt.name == 'hue') {
        def lastHue = lastHueMap[bulbId]
        def newHue = evt.value as Double
        if (lastHue != null && Math.abs(newHue - lastHue) > 5) {
            if (settings.enableDebugLogging) log.info "Bulb hue changed externally (${evt.device.displayName} hue: ${evt.value}), aborting."
            if (state.auroraActive) {
                state.auroraActive = false
                state.auroraStarted = false
                restoreBulbStates()
            }
            return
        }
    }
    if (state.auroraSuppressAbort) {
        if (settings.enableDebugLogging) log.debug "Suppressing abort during first cycle for ${evt.device.displayName} ${evt.name}: ${evt.value}"
        return
    }
    // Ignore all other events (including level/saturation changes)
    if (settings.enableDebugLogging) log.debug "Ignoring non-abort event for ${evt.device.displayName} ${evt.name}: ${evt.value}"
    return
}

def auroraLoop() {

    if (settings.enableDebugLogging) log.debug "auroraLoop called: colorBulbs=${colorBulbs}, state.auroraActive=${state.auroraActive}"
    if (!colorBulbs) { if (settings.enableDebugLogging) log.debug "No color bulbs selected"; return }
    if (!state.auroraActive) { if (settings.enableDebugLogging) log.debug "Aurora not active"; return }


    // Only capture bulb states at the very start of the animation, and only if restore is enabled
    if (!state.auroraStarted) {
        if (state.auroraRestoreOnStop) {
            if (settings.enableDebugLogging) log.debug "Capturing bulb states at animation start"
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
                if (settings.enableDebugLogging) log.debug "Priming ${bulb.displayName} to base hue:${baseHue} sat:${baseSaturation} level:${level}"
                try {
                    suppressEventsFor(bulb)
                    // Track last commanded hue
                    setLastHue(bulb, baseHue as Double)
                    bulb.setColor([hue: baseHue, saturation: baseSaturation, level: level])
                } catch (e) {
                    if (settings.enableDebugLogging) log.warn "setColor failed for ${bulb.displayName}: ${e}"
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
        if (settings.enableDebugLogging) log.debug "Setting ${bulb.displayName} to hue:${harmonicHue} sat:${harmonicSaturation} level:${level}"
        try {
            suppressEventsFor(bulb)
            // Track last commanded hue
            setLastHue(bulb, harmonicHue as Double)
            bulb.setColor([hue: harmonicHue, saturation: harmonicSaturation, level: level])
        } catch (e) {
            if (settings.enableDebugLogging) log.warn "setColor failed for ${bulb.displayName}: ${e}"
        }
        state.auroraStep = step + 1
        runInMillis(bulbIntervalMs, auroraLoop)
    } else {
        // Reset for next cycle
        if (state.auroraSuppressAbort) {
            // Only after first cycle: schedule suppression clear after pause
            runIn(Math.round(cyclePauseSec) as int, clearAuroraSuppressAbort)
        }
    state.auroraStep = 0
    state.auroraOrder = null
    state.auroraBaseHue = null
    state.auroraBaseSat = null
    runIn(Math.round(cyclePauseSec) as int, auroraLoop)
    }
}

// Helper to clear abort suppression after pause
def clearAuroraSuppressAbort() {
    state.auroraSuppressAbort = false
    if (settings.enableDebugLogging) log.debug "Abort suppression cleared after first cycle pause."
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
            if (settings.enableDebugLogging) log.warn "Could not capture state for ${bulb.displayName}: ${e}"
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
                    if (settings.enableDebugLogging) log.debug "Restoring ${bulb.displayName} to hue:${prev.hue} sat:${prev.saturation} level:${prev.level} mode:${prev.colorMode}"
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
                if (settings.enableDebugLogging) log.warn "Could not restore state for ${bulb.displayName}: ${e}"
            }
        }
    }
    state.savedBulbStates = null
}

// Switch event handlers
def onSwitchOn(evt) {
    if (!state.auroraActive) {
        state.auroraActive = true
        state.auroraStarted = false
        state.auroraRestoreOnStop = true
        runIn(2, auroraLoop)
    }
}
def onSwitchOff(evt) {
    state.auroraActive = false
    state.auroraStarted = false
    if (state.auroraRestoreOnStop) restoreBulbStates()
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

// Button event handler (toggle)
// Button event handler (start only, with button number check)
def onButtonEvent(evt) {
    def action = settings.startButtonAction ?: "pushed"
    def btnNum = (settings.startButtonNumber ?: 1) as Integer
    if (settings.enableDebugLogging) log.debug "Button event received: name=${evt.name}, value=${evt.value}, data=${evt.data}, device=${evt.device}, action=${action}, btnNum=${btnNum}"
    def eventAction = evt.name
    def eventBtnNum = null
    try {
        if (evt.value) {
            eventBtnNum = evt.value as Integer
            if (settings.enableDebugLogging) log.debug "Parsed buttonNumber from evt.value: ${eventBtnNum}"
        } else if (evt.data) {
            def json = groovy.json.JsonSlurper.newInstance().parseText(evt.data)
            eventBtnNum = json.buttonNumber as Integer
            if (settings.enableDebugLogging) log.debug "Parsed buttonNumber from evt.data: ${eventBtnNum}"
        } else {
            eventBtnNum = 1 // fallback for single-button
            if (settings.enableDebugLogging) log.debug "Fallback to buttonNumber 1"
        }
    } catch (e) {
        eventBtnNum = 1
        if (settings.enableDebugLogging) log.warn "Exception parsing button number: ${e}"
    }
    if (settings.enableDebugLogging) log.debug "Comparing eventAction=${eventAction} to action=${action}, eventBtnNum=${eventBtnNum} to btnNum=${btnNum}"
    if (eventAction == action && eventBtnNum == btnNum) {
        if (settings.enableDebugLogging) log.debug "Button event matched, starting animation."
        if (!state.auroraActive) {
            state.auroraActive = true
            state.auroraStarted = false
            state.auroraRestoreOnStop = false
            runIn(2, auroraLoop)
        }
    }
}
