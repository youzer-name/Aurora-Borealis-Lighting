# Aurora Borealis Lighting

A Hubitat Elevation app that creates mesmerizing aurora borealis (northern lights) effects by cycling your color-capable smart lights through a selected range of hues with configurable transition timing.

## Requirements

- Hubitat Elevation hub (C-3, C-5, C-7, or C-8)
- One or more color-capable smart lights (bulbs, strips, etc.) that support the Color Control capability
- Hubitat app code installation capability

## Installation

### Step 1: Install the App Code

1. Log into your Hubitat web interface
2. Navigate to **Apps Code** from the left sidebar
3. Click **+ New App** in the top right
4. Copy the entire contents of `AuroraBorealisLighting.groovy` and paste it into the code editor
5. Click **Save**

### Step 2: Create an App Instance

1. Navigate to **Apps** from the left sidebar
2. Click **+ Add User App** in the top right
3. Select **Aurora Borealis Lighting** from the list
4. Configure your settings (see Configuration section below)
5. Click **Done**

## Configuration

### Enable debug logging?
- Enable for verbose logging for troubleshooting any issues.

### Select Lights
- Which color lights?: Select one or more color-capable lights that will participate in the aurora effect.  
  - Note: The device setting in Preferences for "Transition  time" may have a significant effect on the overall animation effect
  - Try a longer Transition time setting if bulbs are jumping between colors without a smooth transition

### Timing Settings
- **Set a bulb every (seconds)**: Enter the time between each individual bulb color changing during a cycle
- **Then pause (seconds)**: Enter the time to pause before moving to a new cycle with a new base hue

### Brightness
- **Brightness level**: Sets the brightness for all selected lights (1-100). Default is 75.

### Start/Stop Control (Switch)
- **Switch to Start/Stop Effect (optional)**: Optionally link a switch to control when the aurora effect is active. When the switch turns on, the effect starts. When off, it stops.
  - When using a switch to start/stop, the bulb state will be captured before starting the animation and restored after stopping the animation.

### Start Control (Button)
- Select a button device, button number, and button action that will start the animation.
  - When starting and stopping using a button, there is no capture/restore done.

### Stop Control (Button)
- Select a button device, button number, and button action that will stop the animation.
  - When starting and stopping using a button, there is no capture/restore done.


### Color Palette

The app randomly selects a base hue between 50 and 89 and saturation between 85 and 99 at the beginning of each cycle.

- **Random Design**: The base color is randomly selected for the start of each cycle

### How Transitions Work

1. The app picks a random base hue and saturation
2. The app sets each bulb to a color similar to the base with a random offset
3. The bulbs are set in a random order with the user-selected interval between each change
4. After setting all bulbs, the animation holds that state for the user selected 'Then pause' interval
5. The process repeats indefinitely in a loop

## Usage Notes

### Starting the Effect

- **Via switch**
  - The effect starts when the selected switch turns on.  
  - The state of the bulbs prior to animation is captured.

- **Via button**
  - The effect starts on the selected button event.  
  - No capture is performed.

### Stopping the Effect

- **Via switch**
  - The effect stops when the selected switch is turned off.
  - The bulbs are restored to their prior state.

- **Via Button**
  - The effect stops on the selected button event.
  - No restore is performed.

- **Via interrupt/override**
  - The effect stops when any selected bulb is turned off, set to a color temperature, or set to a color different than its last commanded color


## License

Licensed under the Apache License, Version 2.0. See the LICENSE file or visit http://www.apache.org/licenses/LICENSE-2.0

## Contributing

Feel free to submit issues, feature requests, or pull requests to improve this app!
