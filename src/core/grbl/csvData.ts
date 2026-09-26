// 本文件由 scripts/gen-data.mjs 从 LaserGRBL 原始 CSV 资源自动生成，请勿手工编辑。
/* eslint-disable */
export type CodeEntry = string[]

/** GRBL 设置项说明: 键为参数编号, 值为 [名称, 单位, 说明] */
export const SETTING_CODES: Record<string, Record<string, CodeEntry>> = {
  "v0.8": {
    "0": [
      "X-axis travel resolution",
      "step/mm",
      "X-axis travel resolution in steps per millimeter."
    ],
    "1": [
      "Y-axis travel resolution",
      "step/mm",
      "Y-axis travel resolution in steps per millimeter."
    ],
    "2": [
      "Z-axis travel resolution",
      "step/mm",
      "Z-axis travel resolution in steps per millimeter."
    ],
    "3": [
      "Step pulse time",
      "microseconds",
      "Sets time length per step."
    ],
    "4": [
      "Default feed rates",
      "mm/min",
      "This setting sets the default seek rates (G0) after Grbl powers on and initializes"
    ],
    "5": [
      "Default seek rates",
      "mm/min",
      "This setting sets the default feed rates(G1 G2 G3) after Grbl powers on and initializes"
    ],
    "6": [
      "Step port invert mask",
      "int:00000000",
      "Inverts the step signal."
    ],
    "7": [
      "step idle delay",
      "msec",
      "Sets a short hold delay when stopping to let dynamics settle before disabling steppers. Value 255 keeps motors enabled with no delay."
    ],
    "8": [
      "acceleration",
      "mm/sec^2",
      "Used for motion planning to not exceed motor torque and lose steps."
    ],
    "9": [
      "junction deviation",
      "mm",
      "Sets how fast Grbl travels through consecutive motions. Lower value slows it down."
    ],
    "10": [
      "arc",
      "mm/segment",
      ""
    ],
    "11": [
      "n-arc correction",
      "int",
      "This is an advanced setting that shouldn't be changed unless there are circumstances that you need to."
    ],
    "12": [
      "n-decimals",
      "int",
      "Set how many decimal places all of the floating point values Grbl reports."
    ],
    "13": [
      "report inches",
      "bool",
      "Enables inch units when returning any position and rate value that is not a settings value."
    ],
    "14": [
      "auto start",
      "bool",
      ""
    ],
    "15": [
      "invert step enable",
      "bool",
      ""
    ],
    "16": [
      "hard limits",
      "bool",
      ""
    ],
    "17": [
      "homing cycle",
      "bool",
      ""
    ],
    "18": [
      "homing dir invert mask",
      "int:00000000",
      ""
    ],
    "19": [
      "homing feed",
      "mm/min",
      ""
    ],
    "20": [
      "homing seek",
      "mm/min",
      ""
    ],
    "21": [
      "homing debounce",
      "msec",
      ""
    ],
    "22": [
      "homing pull-off",
      "mm",
      ""
    ],
    "$-Code": [
      "Setting",
      "Units",
      "Setting Description"
    ]
  },
  "v0.9": {
    "0": [
      "Step pulse time",
      "microseconds",
      "Sets time length per step. Minimum 3usec."
    ],
    "1": [
      "Step idle delay",
      "milliseconds",
      "Sets a short hold delay when stopping to let dynamics settle before disabling steppers. Value 255 keeps motors enabled with no delay."
    ],
    "2": [
      "Step pulse invert",
      "mask",
      "Inverts the step signal. Set axis bit to invert (00000ZYX)."
    ],
    "3": [
      "Step direction invert",
      "mask",
      "Inverts the direction signal. Set axis bit to invert (00000ZYX)."
    ],
    "4": [
      "Invert step enable pin",
      "boolean",
      "Inverts the stepper driver enable pin signal."
    ],
    "5": [
      "Invert limit pins",
      "boolean",
      "Inverts the all of the limit input pins."
    ],
    "6": [
      "Invert probe pin",
      "boolean",
      "Inverts the probe input pin signal."
    ],
    "10": [
      "Status report options",
      "mask",
      "Alters data included in status reports."
    ],
    "11": [
      "Junction deviation",
      "millimeters",
      "Sets how fast Grbl travels through consecutive motions. Lower value slows it down."
    ],
    "12": [
      "Arc tolerance",
      "millimeters",
      "Sets the G2 and G3 arc tracing accuracy based on radial error. Beware: A very small value may effect performance."
    ],
    "13": [
      "Report in inches",
      "boolean",
      "Enables inch units when returning any position and rate value that is not a settings value."
    ],
    "20": [
      "Soft limits enable",
      "boolean",
      "Enables soft limits checks within machine travel and sets alarm when exceeded. Requires homing."
    ],
    "21": [
      "Hard limits enable",
      "boolean",
      "Enables hard limits. Immediately halts motion and throws an alarm when switch is triggered."
    ],
    "22": [
      "Homing cycle enable",
      "boolean",
      "Enables homing cycle. Requires limit switches on all axes."
    ],
    "23": [
      "Homing direction invert",
      "mask",
      "Homing searches for a switch in the positive direction. Set axis bit (00000ZYX) to search in negative direction."
    ],
    "24": [
      "Homing locate feed rate",
      "mm/min",
      "Feed rate to slowly engage limit switch to determine its location accurately."
    ],
    "25": [
      "Homing search seek rate",
      "mm/min",
      "Seek rate to quickly find the limit switch before the slower locating phase."
    ],
    "26": [
      "Homing switch debounce delay",
      "milliseconds",
      "Sets a short delay between phases of homing cycle to let a switch debounce."
    ],
    "27": [
      "Homing switch pull-off distance",
      "millimeters",
      "Retract distance after triggering switch to disengage it. Homing will fail if switch isn't cleared."
    ],
    "100": [
      "X-axis travel resolution",
      "step/mm",
      "X-axis travel resolution in steps per millimeter."
    ],
    "101": [
      "Y-axis travel resolution",
      "step/mm",
      "Y-axis travel resolution in steps per millimeter."
    ],
    "102": [
      "Z-axis travel resolution",
      "step/mm",
      "Z-axis travel resolution in steps per millimeter."
    ],
    "110": [
      "X-axis maximum rate",
      "mm/min",
      "X-axis maximum rate. Used as G0 rapid rate."
    ],
    "111": [
      "Y-axis maximum rate",
      "mm/min",
      "Y-axis maximum rate. Used as G0 rapid rate."
    ],
    "112": [
      "Z-axis maximum rate",
      "mm/min",
      "Z-axis maximum rate. Used as G0 rapid rate."
    ],
    "120": [
      "X-axis acceleration",
      "mm/sec^2",
      "X-axis acceleration. Used for motion planning to not exceed motor torque and lose steps."
    ],
    "121": [
      "Y-axis acceleration",
      "mm/sec^2",
      "Y-axis acceleration. Used for motion planning to not exceed motor torque and lose steps."
    ],
    "122": [
      "Z-axis acceleration",
      "mm/sec^2",
      "Z-axis acceleration. Used for motion planning to not exceed motor torque and lose steps."
    ],
    "130": [
      "X-axis maximum travel",
      "millimeters",
      "Maximum X-axis travel distance from homing switch. Determines valid machine space for soft-limits and homing search distances."
    ],
    "131": [
      "Y-axis maximum travel",
      "millimeters",
      "Maximum Y-axis travel distance from homing switch. Determines valid machine space for soft-limits and homing search distances."
    ],
    "132": [
      "Z-axis maximum travel",
      "millimeters",
      "Maximum Z-axis travel distance from homing switch. Determines valid machine space for soft-limits and homing search distances."
    ],
    "$-Code": [
      "Setting",
      "Units",
      "Setting Description"
    ]
  },
  "v1.1": {
    "0": [
      "Step pulse time",
      "microseconds",
      "Sets time length per step. Minimum 3usec."
    ],
    "1": [
      "Step idle delay",
      "milliseconds",
      "Sets a short hold delay when stopping to let dynamics settle before disabling steppers. Value 255 keeps motors enabled with no delay."
    ],
    "2": [
      "Step pulse invert",
      "mask",
      "Inverts the step signal. Set axis bit to invert (00000ZYX)."
    ],
    "3": [
      "Step direction invert",
      "mask",
      "Inverts the direction signal. Set axis bit to invert (00000ZYX)."
    ],
    "4": [
      "Invert step enable pin",
      "boolean",
      "Inverts the stepper driver enable pin signal."
    ],
    "5": [
      "Invert limit pins",
      "boolean",
      "Inverts all of the limit input pins."
    ],
    "6": [
      "Invert probe pin",
      "boolean",
      "Inverts the probe input pin signal."
    ],
    "10": [
      "Status report options",
      "mask",
      "Alters data included in status reports."
    ],
    "11": [
      "Junction deviation",
      "millimeters",
      "Sets how fast Grbl travels through consecutive motions. Lower value slows it down."
    ],
    "12": [
      "Arc tolerance",
      "millimeters",
      "Sets the G2 and G3 arc tracing accuracy based on radial error. Beware: A very small value may effect performance."
    ],
    "13": [
      "Report in inches",
      "boolean",
      "Enables inch units when returning any position and rate value that is not a settings value."
    ],
    "20": [
      "Soft limits enable",
      "boolean",
      "Enables soft limits checks within machine travel and sets alarm when exceeded. Requires homing."
    ],
    "21": [
      "Hard limits enable",
      "boolean",
      "Enables hard limits. Immediately halts motion and throws an alarm when switch is triggered."
    ],
    "22": [
      "Homing cycle enable",
      "boolean",
      "Enables homing cycle. Requires limit switches on all axes."
    ],
    "23": [
      "Homing direction invert",
      "mask",
      "Homing searches for a switch in the positive direction. Set axis bit (00000ZYX) to search in negative direction."
    ],
    "24": [
      "Homing locate feed rate",
      "mm/min",
      "Feed rate to slowly engage limit switch to determine its location accurately."
    ],
    "25": [
      "Homing search seek rate",
      "mm/min",
      "Seek rate to quickly find the limit switch before the slower locating phase."
    ],
    "26": [
      "Homing switch debounce delay",
      "milliseconds",
      "Sets a short delay between phases of homing cycle to let a switch debounce."
    ],
    "27": [
      "Homing switch pull-off distance",
      "millimeters",
      "Retract distance after triggering switch to disengage it. Homing will fail if switch isn't cleared."
    ],
    "30": [
      "Maximum spindle speed",
      "RPM",
      "Maximum spindle speed. Sets PWM to 100% duty cycle."
    ],
    "31": [
      "Minimum spindle speed",
      "RPM",
      "Minimum spindle speed. Sets PWM to 0.4% or lowest duty cycle."
    ],
    "32": [
      "Laser-mode enable",
      "boolean",
      "Enables laser mode. Consecutive G1/2/3 commands will not halt when spindle speed is changed."
    ],
    "100": [
      "X-axis travel resolution",
      "step/mm",
      "X-axis travel resolution in steps per millimeter."
    ],
    "101": [
      "Y-axis travel resolution",
      "step/mm",
      "Y-axis travel resolution in steps per millimeter."
    ],
    "102": [
      "Z-axis travel resolution",
      "step/mm",
      "Z-axis travel resolution in steps per millimeter."
    ],
    "110": [
      "X-axis maximum rate",
      "mm/min",
      "X-axis maximum rate. Used as G0 rapid rate."
    ],
    "111": [
      "Y-axis maximum rate",
      "mm/min",
      "Y-axis maximum rate. Used as G0 rapid rate."
    ],
    "112": [
      "Z-axis maximum rate",
      "mm/min",
      "Z-axis maximum rate. Used as G0 rapid rate."
    ],
    "120": [
      "X-axis acceleration",
      "mm/sec^2",
      "X-axis acceleration. Used for motion planning to not exceed motor torque and lose steps."
    ],
    "121": [
      "Y-axis acceleration",
      "mm/sec^2",
      "Y-axis acceleration. Used for motion planning to not exceed motor torque and lose steps."
    ],
    "122": [
      "Z-axis acceleration",
      "mm/sec^2",
      "Z-axis acceleration. Used for motion planning to not exceed motor torque and lose steps."
    ],
    "130": [
      "X-axis maximum travel",
      "millimeters",
      "Maximum X-axis travel distance from homing switch. Determines valid machine space for soft-limits and homing search distances."
    ],
    "131": [
      "Y-axis maximum travel",
      "millimeters",
      "Maximum Y-axis travel distance from homing switch. Determines valid machine space for soft-limits and homing search distances."
    ],
    "132": [
      "Z-axis maximum travel",
      "millimeters",
      "Maximum Z-axis travel distance from homing switch. Determines valid machine space for soft-limits and homing search distances."
    ],
    "$-Code": [
      "Setting",
      "Units",
      "Setting Description"
    ]
  },
  "ortur.v1.4.x": {
    "0": [
      "Step pulse time",
      "microseconds",
      "Sets time length per step. Minimum 3usec."
    ],
    "1": [
      "Step idle delay",
      "milliseconds",
      "Sets a short hold delay when stopping to let dynamics settle before disabling steppers. Value 255 keeps motors enabled with no delay."
    ],
    "2": [
      "Step pulse invert",
      "mask",
      "Inverts the step signal. Set axis bit to invert (00000ZYX)."
    ],
    "3": [
      "Step direction invert",
      "mask",
      "Inverts the direction signal. Set axis bit to invert (00000ZYX)."
    ],
    "4": [
      "Invert step enable pin",
      "boolean",
      "Inverts the stepper driver enable pin signal."
    ],
    "5": [
      "Invert limit pins",
      "boolean",
      "Inverts the all of the limit input pins."
    ],
    "6": [
      "Invert probe pin",
      "boolean",
      "Inverts the probe input pin signal."
    ],
    "10": [
      "Status report options",
      "mask",
      "Alters data included in status reports."
    ],
    "11": [
      "Junction deviation",
      "millimeters",
      "Sets how fast Grbl travels through consecutive motions. Lower value slows it down."
    ],
    "12": [
      "Arc tolerance",
      "millimeters",
      "Sets the G2 and G3 arc tracing accuracy based on radial error. Beware: A very small value may effect performance."
    ],
    "13": [
      "Report in inches",
      "boolean",
      "Enables inch units when returning any position and rate value that is not a settings value."
    ],
    "20": [
      "Soft limits enable",
      "boolean",
      "Enables soft limits checks within machine travel and sets alarm when exceeded. Requires homing."
    ],
    "21": [
      "Hard limits enable",
      "boolean",
      "Enables hard limits. Immediately halts motion and throws an alarm when switch is triggered."
    ],
    "22": [
      "Homing cycle enable",
      "boolean",
      "Enables homing cycle. Requires limit switches on all axes."
    ],
    "23": [
      "Homing direction invert",
      "mask",
      "Homing searches for a switch in the positive direction. Set axis bit (00000ZYX) to search in negative direction."
    ],
    "24": [
      "Homing locate feed rate",
      "mm/min",
      "Feed rate to slowly engage limit switch to determine its location accurately."
    ],
    "25": [
      "Homing search seek rate",
      "mm/min",
      "Seek rate to quickly find the limit switch before the slower locating phase."
    ],
    "26": [
      "Homing switch debounce delay",
      "milliseconds",
      "Sets a short delay between phases of homing cycle to let a switch debounce."
    ],
    "27": [
      "Homing switch pull-off distance",
      "millimeters",
      "Retract distance after triggering switch to disengage it. Homing will fail if switch isn't cleared."
    ],
    "30": [
      "Maximum spindle speed",
      "RPM",
      "Maximum spindle speed. Sets PWM to 100% duty cycle."
    ],
    "31": [
      "Minimum spindle speed",
      "RPM",
      "Minimum spindle speed. Sets PWM to 0.4% or lowest duty cycle."
    ],
    "32": [
      "Laser-mode enable",
      "boolean",
      "Enables laser mode. Consecutive G1/2/3 commands will not halt when spindle speed is changed."
    ],
    "33": [
      "G-sensor threshold",
      "double",
      "Safety threshold for shock and movement detection"
    ],
    "100": [
      "X-axis travel resolution",
      "step/mm",
      "X-axis travel resolution in steps per millimeter."
    ],
    "101": [
      "Y-axis travel resolution",
      "step/mm",
      "Y-axis travel resolution in steps per millimeter."
    ],
    "102": [
      "Z-axis travel resolution",
      "step/mm",
      "Z-axis travel resolution in steps per millimeter."
    ],
    "110": [
      "X-axis maximum rate",
      "mm/min",
      "X-axis maximum rate. Used as G0 rapid rate."
    ],
    "111": [
      "Y-axis maximum rate",
      "mm/min",
      "Y-axis maximum rate. Used as G0 rapid rate."
    ],
    "112": [
      "Z-axis maximum rate",
      "mm/min",
      "Z-axis maximum rate. Used as G0 rapid rate."
    ],
    "120": [
      "X-axis acceleration",
      "mm/sec^2",
      "X-axis acceleration. Used for motion planning to not exceed motor torque and lose steps."
    ],
    "121": [
      "Y-axis acceleration",
      "mm/sec^2",
      "Y-axis acceleration. Used for motion planning to not exceed motor torque and lose steps."
    ],
    "122": [
      "Z-axis acceleration",
      "mm/sec^2",
      "Z-axis acceleration. Used for motion planning to not exceed motor torque and lose steps."
    ],
    "130": [
      "X-axis maximum travel",
      "millimeters",
      "Maximum X-axis travel distance from homing switch. Determines valid machine space for soft-limits and homing search distances."
    ],
    "131": [
      "Y-axis maximum travel",
      "millimeters",
      "Maximum Y-axis travel distance from homing switch. Determines valid machine space for soft-limits and homing search distances."
    ],
    "132": [
      "Z-axis maximum travel",
      "millimeters",
      "Maximum Z-axis travel distance from homing switch. Determines valid machine space for soft-limits and homing search distances."
    ],
    "$-Code": [
      "Setting",
      "Units",
      "Setting Description"
    ]
  },
  "ortur.v1.5.x": {
    "0": [
      "Step pulse time",
      "microseconds",
      "Sets time length per step. Minimum 3 microseconds."
    ],
    "1": [
      "Step idle delay",
      "milliseconds",
      "Sets a short hold delay when stopping to let dynamics settle before disabling steppers. Value 255 keeps motors enabled."
    ],
    "2": [
      "Step pulse invert",
      "mask",
      "Inverts the step signals (active low)."
    ],
    "3": [
      "Step direction invert",
      "mask",
      "Inverts the direction signals (active low)."
    ],
    "4": [
      "Invert step enable pin",
      "boolean",
      "Inverts the stepper driver enable signals (active low). If the stepper drivers shares the same enable signal only X is used."
    ],
    "5": [
      "Invert limit pins",
      "mask",
      "Inverts the axis limit input signals."
    ],
    "6": [
      "Invert probe pin",
      "boolean",
      "Inverts the probe input pin signal."
    ],
    "10": [
      "Status report options",
      "mask",
      "Specifies optional data included in status reports."
    ],
    "11": [
      "Junction deviation",
      "mm",
      "Sets how fast Grbl travels through consecutive motions. Lower value slows it down."
    ],
    "12": [
      "Arc tolerance",
      "mm",
      "Sets the G2 and G3 arc tracing accuracy based on radial error. Beware: A very small value may effect performance."
    ],
    "13": [
      "Report in inches",
      "boolean",
      "Enables inch units when returning any position and rate value that is not a settings value."
    ],
    "14": [
      "Invert control pins",
      "mask",
      "Inverts the control signals (active low)."
    ],
    "15": [
      "Invert coolant pins",
      "mask",
      "Inverts the coolant and mist signals (active low)."
    ],
    "16": [
      "Invert spindle signals",
      "mask",
      "Inverts the spindle on counterclockwise and PWM signals (active low)."
    ],
    "17": [
      "Pullup disable control pins",
      "mask",
      "Disable the control signals pullup resistors. Potentially enables pulldown resistor if available."
    ],
    "18": [
      "Pullup disable limit pins",
      "mask",
      "Disable the limit signals pullup resistors. Potentially enables pulldown resistor if available."
    ],
    "19": [
      "Pullup disable probe pin",
      "boolean",
      "Disable the probe signal pullup resistor. Potentially enables pulldown resistor if available."
    ],
    "20": [
      "Soft limits enable",
      "boolean",
      "Enables soft limits checks within machine travel and sets alarm when exceeded. Requires homing."
    ],
    "21": [
      "Hard limits enable",
      "mask",
      "When enabled immediately halts motion and throws an alarm when switch is triggered. In strict mode only homing is possible after switch is triggered."
    ],
    "22": [
      "Homing cycle enable",
      "boolean",
      "Enables homing cycle. Requires limit switches on all axes."
    ],
    "23": [
      "Homing direction invert",
      "mask",
      "Homing searches for a switch in the positive direction. Set axis bit to search in negative direction."
    ],
    "24": [
      "Homing locate feed rate",
      "mm/min",
      "Feed rate to slowly engage limit switch to determine its location accurately."
    ],
    "25": [
      "Homing search seek rate",
      "mm/min",
      "Seek rate to quickly find the limit switch before the slower locating phase."
    ],
    "26": [
      "Homing switch debounce delay",
      "milliseconds",
      "Sets a short delay between phases of homing cycle to let a switch debounce."
    ],
    "27": [
      "Homing switch pull off distance",
      "mm",
      "Retract distance after triggering switch to disengage it. Homing will fail if switch isn't cleared."
    ],
    "28": [
      "G73 Retract distance",
      "mm",
      "G73 retract distance (for chip breaking drilling)."
    ],
    "29": [
      "Pulse delay",
      "microseconds",
      "Step pulse delay."
    ],
    "30": [
      "Maximum spindle speed",
      "RPM",
      "Maximum spindle speed. Sets PWM to maximum duty cycle."
    ],
    "31": [
      "Minimum spindle speed",
      "RPM",
      "Minimum spindle speed. Sets PWM to minimum duty cycle."
    ],
    "32": [
      "Mode of operation",
      "integer",
      "Laser mode: consecutive G1/2/3 commands will not halt when spindle speed is changed. Lathe mode: allows use of G7/G8/G96 and G97."
    ],
    "33": [
      "PWM frequency",
      "Hz",
      "PWM frequency."
    ],
    "34": [
      "PWM off value",
      "percent",
      "PWM off value in percent (duty cycle)."
    ],
    "35": [
      "PWM min value",
      "percent",
      "PWM min value in percent (duty cycle)."
    ],
    "36": [
      "PWM max value",
      "percent",
      "PWM max value in percent (duty cycle)."
    ],
    "37": [
      "Steppers deenergize",
      "mask",
      "Specifies which steppers not to disable when stopped."
    ],
    "39": [
      "Enable legacy RT commands",
      "boolean",
      "Enables \"normal\" processing of ? ! and ~ characters when part of $setting or comment. If disabled then they are added to the input string instead."
    ],
    "40": [
      "Limit jog commands",
      "boolean",
      "Limit jog commands to machine limits for homed axes."
    ],
    "41": [
      "Safety Door",
      "boolean",
      "Enable Safety Door."
    ],
    "42": [
      "Safety Door",
      "mask",
      "Define which axis that performs the parking motion."
    ],
    "43": [
      "Homing passes",
      "integer",
      "Number of homing passes. Minimum 1 maximum 128."
    ],
    "44": [
      "Axes homing",
      "mask",
      "Axes to home in first pass."
    ],
    "45": [
      "Axes homing",
      "mask",
      "Axes to home in second pass."
    ],
    "46": [
      "Axes homing",
      "mask",
      "Axes to home in third pass."
    ],
    "56": [
      "Safety Door",
      "boolean",
      "Spindle pull out and plunge distance in mm. Incremental distance."
    ],
    "57": [
      "Safety Door",
      "integer",
      "Pull out/plunge slow feed rate in mm/min."
    ],
    "58": [
      "Safety Door",
      "integer",
      "Parking axis target. In mm  as machine coordinate"
    ],
    "59": [
      "Safety Door",
      "integer",
      "Parking fast rate after pull out in mm/min."
    ],
    "60": [
      "Restore overrides",
      "boolean",
      "Restore overrides to default values at program end."
    ],
    "61": [
      "Ignore door when idle",
      "boolean",
      "Enable this if it is desirable to open the safety door when in IDLE mode (eg. for jogging)."
    ],
    "62": [
      "Sleep enable",
      "boolean",
      "Enable sleep mode."
    ],
    "63": [
      "Disable laser",
      "boolean",
      "Disable laser during hold."
    ],
    "64": [
      "Force init alarm",
      "boolean",
      "Starts Grbl in alarm mode after a cold reset."
    ],
    "65": [
      "Check limits at init",
      "boolean",
      "If limit switches are engaged after reset this forces Grbl to start in alarm mode."
    ],
    "100": [
      "X-axis travel resolution",
      "step/mm",
      "X-axis travel resolution in steps per millimeter."
    ],
    "101": [
      "Y-axis travel resolution",
      "step/mm",
      "Y-axis travel resolution in steps per millimeter."
    ],
    "102": [
      "Z-axis travel resolution",
      "step/mm",
      "Z-axis travel resolution in steps per millimeter."
    ],
    "110": [
      "X-axis maximum rate",
      "mm/min",
      "X-axis maximum rate. Used as G0 rapid rate."
    ],
    "111": [
      "Y-axis maximum rate",
      "mm/min",
      "Y-axis maximum rate. Used as G0 rapid rate."
    ],
    "112": [
      "Z-axis maximum rate",
      "mm/min",
      "Z-axis maximum rate. Used as G0 rapid rate."
    ],
    "120": [
      "X-axis acceleration",
      "mm/sec^2",
      "X-axis acceleration. Used for motion planning to not exceed motor torque and lose."
    ],
    "121": [
      "Y-axis acceleration",
      "mm/sec^2",
      "Y-axis acceleration. Used for motion planning to not exceed motor torque and lose."
    ],
    "122": [
      "Z-axis acceleration",
      "mm/sec^2",
      "Z-axis acceleration. Used for motion planning to not exceed motor torque and lose."
    ],
    "130": [
      "X-axis maximum travel",
      "mm",
      "Maximum X-axis travel distance from homing switch. Determines valid machine space for."
    ],
    "131": [
      "Y-axis maximum travel",
      "mm",
      "Maximum Y-axis travel distance from homing switch. Determines valid machine space for."
    ],
    "132": [
      "Z-axis maximum travel",
      "mm",
      "Maximum Z-axis travel distance from homing switch. Determines valid machine space for."
    ],
    "259": [
      "Ortur Flame Sensor Debug",
      "boolean",
      "Flame Sensor Debug Mode (On/OFF)"
    ],
    "260": [
      "Ortur Flame Sensor Delta",
      "integer",
      "Flame Sensor Trigger Delta Value"
    ],
    "261": [
      "Ortur Flame Sensor Count",
      "integer",
      "Flame Sensor Trigger Count Treshold"
    ],
    "262": [
      "Ortur Gshock Sensor Threshold",
      "integer",
      "Gshock Sensor Treshold"
    ],
    "263": [
      "Ortur Auto Power Off",
      "minutes",
      "Auto Power Off in Minutes"
    ],
    "$-Code": [
      "Setting",
      "Units",
      "Setting Description"
    ]
  },
  "ortur.v1.7.x": {
    "0": [
      "Step pulse time",
      "microseconds",
      "Sets time length per step. Minimum 3 microseconds."
    ],
    "1": [
      "Step idle delay",
      "milliseconds",
      "Sets a short hold delay when stopping to let dynamics settle before disabling steppers. Value 255 keeps motors enabled."
    ],
    "2": [
      "Step pulse invert",
      "mask",
      "Inverts the step signals (active low)."
    ],
    "3": [
      "Step direction invert",
      "mask",
      "Inverts the direction signals (active low)."
    ],
    "4": [
      "Invert step enable pin",
      "boolean",
      "Inverts the stepper driver enable signals (active low). If the stepper drivers shares the same enable signal only X is used."
    ],
    "5": [
      "Invert limit pins",
      "mask",
      "Inverts the axis limit input signals."
    ],
    "6": [
      "Invert probe pin",
      "boolean",
      "Inverts the probe input pin signal."
    ],
    "10": [
      "Status report options",
      "mask",
      "Specifies optional data included in status reports."
    ],
    "11": [
      "Junction deviation",
      "mm",
      "Sets how fast Grbl travels through consecutive motions. Lower value slows it down."
    ],
    "12": [
      "Arc tolerance",
      "mm",
      "Sets the G2 and G3 arc tracing accuracy based on radial error. Beware: A very small value may effect performance."
    ],
    "13": [
      "Report in inches",
      "boolean",
      "Enables inch units when returning any position and rate value that is not a settings value."
    ],
    "14": [
      "Invert control pins",
      "mask",
      "Inverts the control signals (active low)."
    ],
    "15": [
      "Invert coolant pins",
      "mask",
      "Inverts the coolant and mist signals (active low)."
    ],
    "16": [
      "Invert spindle signals",
      "mask",
      "Inverts the spindle on counterclockwise and PWM signals (active low)."
    ],
    "17": [
      "Pullup disable control pins",
      "mask",
      "Disable the control signals pullup resistors. Potentially enables pulldown resistor if available."
    ],
    "18": [
      "Pullup disable limit pins",
      "mask",
      "Disable the limit signals pullup resistors. Potentially enables pulldown resistor if available."
    ],
    "19": [
      "Pullup disable probe pin",
      "boolean",
      "Disable the probe signal pullup resistor. Potentially enables pulldown resistor if available."
    ],
    "20": [
      "Soft limits enable",
      "boolean",
      "Enables soft limits checks within machine travel and sets alarm when exceeded. Requires homing."
    ],
    "21": [
      "Hard limits enable",
      "mask",
      "When enabled immediately halts motion and throws an alarm when switch is triggered. In strict mode only homing is possible after switch is triggered."
    ],
    "22": [
      "Homing cycle enable",
      "boolean",
      "Enables homing cycle. Requires limit switches on all axes."
    ],
    "23": [
      "Homing direction invert",
      "mask",
      "Homing searches for a switch in the positive direction. Set axis bit to search in negative direction."
    ],
    "24": [
      "Homing locate feed rate",
      "mm/min",
      "Feed rate to slowly engage limit switch to determine its location accurately."
    ],
    "25": [
      "Homing search seek rate",
      "mm/min",
      "Seek rate to quickly find the limit switch before the slower locating phase."
    ],
    "26": [
      "Homing switch debounce delay",
      "milliseconds",
      "Sets a short delay between phases of homing cycle to let a switch debounce."
    ],
    "27": [
      "Homing switch pull off distance",
      "mm",
      "Retract distance after triggering switch to disengage it. Homing will fail if switch isn't cleared."
    ],
    "28": [
      "G73 Retract distance",
      "mm",
      "G73 retract distance (for chip breaking drilling)."
    ],
    "29": [
      "Pulse delay",
      "microseconds",
      "Step pulse delay."
    ],
    "30": [
      "Maximum spindle speed",
      "RPM",
      "Maximum spindle speed. Sets PWM to maximum duty cycle."
    ],
    "31": [
      "Minimum spindle speed",
      "RPM",
      "Minimum spindle speed. Sets PWM to minimum duty cycle."
    ],
    "32": [
      "Mode of operation",
      "integer",
      "Laser mode: consecutive G1/2/3 commands will not halt when spindle speed is changed. Lathe mode: allows use of G7/G8/G96 and G97."
    ],
    "33": [
      "PWM frequency",
      "Hz",
      "PWM frequency."
    ],
    "34": [
      "PWM off value",
      "percent",
      "PWM off value in percent (duty cycle)."
    ],
    "35": [
      "PWM min value",
      "percent",
      "PWM min value in percent (duty cycle)."
    ],
    "36": [
      "PWM max value",
      "percent",
      "PWM max value in percent (duty cycle)."
    ],
    "37": [
      "Steppers deenergize",
      "mask",
      "Specifies which steppers not to disable when stopped."
    ],
    "39": [
      "Enable legacy RT commands",
      "boolean",
      "Enables \"normal\" processing of ? ! and ~ characters when part of $setting or comment. If disabled then they are added to the input string instead."
    ],
    "40": [
      "Limit jog commands",
      "boolean",
      "Limit jog commands to machine limits for homed axes."
    ],
    "41": [
      "Safety Door",
      "boolean",
      "Enable Safety Door."
    ],
    "42": [
      "Safety Door",
      "mask",
      "Define which axis that performs the parking motion."
    ],
    "43": [
      "Homing passes",
      "integer",
      "Number of homing passes. Minimum 1 maximum 128."
    ],
    "44": [
      "Axes homing",
      "mask",
      "Axes to home in first pass."
    ],
    "45": [
      "Axes homing",
      "mask",
      "Axes to home in second pass."
    ],
    "46": [
      "Axes homing",
      "mask",
      "Axes to home in third pass."
    ],
    "56": [
      "Safety Door",
      "boolean",
      "Spindle pull out and plunge distance in mm. Incremental distance."
    ],
    "57": [
      "Safety Door",
      "integer",
      "Pull out/plunge slow feed rate in mm/min."
    ],
    "58": [
      "Safety Door",
      "integer",
      "Parking axis target. In mm  as machine coordinate"
    ],
    "59": [
      "Safety Door",
      "integer",
      "Parking fast rate after pull out in mm/min."
    ],
    "60": [
      "Restore overrides",
      "boolean",
      "Restore overrides to default values at program end."
    ],
    "61": [
      "Ignore door when idle",
      "boolean",
      "Enable this if it is desirable to open the safety door when in IDLE mode (eg. for jogging)."
    ],
    "62": [
      "Sleep enable",
      "boolean",
      "Enable sleep mode."
    ],
    "63": [
      "Disable laser",
      "boolean",
      "Disable laser during hold."
    ],
    "64": [
      "Force init alarm",
      "boolean",
      "Starts Grbl in alarm mode after a cold reset."
    ],
    "65": [
      "Check limits at init",
      "boolean",
      "If limit switches are engaged after reset this forces Grbl to start in alarm mode."
    ],
    "100": [
      "X-axis travel resolution",
      "step/mm",
      "X-axis travel resolution in steps per millimeter."
    ],
    "101": [
      "Y-axis travel resolution",
      "step/mm",
      "Y-axis travel resolution in steps per millimeter."
    ],
    "102": [
      "Z-axis travel resolution",
      "step/mm",
      "Z-axis travel resolution in steps per millimeter."
    ],
    "110": [
      "X-axis maximum rate",
      "mm/min",
      "X-axis maximum rate. Used as G0 rapid rate."
    ],
    "111": [
      "Y-axis maximum rate",
      "mm/min",
      "Y-axis maximum rate. Used as G0 rapid rate."
    ],
    "112": [
      "Z-axis maximum rate",
      "mm/min",
      "Z-axis maximum rate. Used as G0 rapid rate."
    ],
    "120": [
      "X-axis acceleration",
      "mm/sec^2",
      "X-axis acceleration. Used for motion planning to not exceed motor torque and lose."
    ],
    "121": [
      "Y-axis acceleration",
      "mm/sec^2",
      "Y-axis acceleration. Used for motion planning to not exceed motor torque and lose."
    ],
    "122": [
      "Z-axis acceleration",
      "mm/sec^2",
      "Z-axis acceleration. Used for motion planning to not exceed motor torque and lose."
    ],
    "130": [
      "X-axis maximum travel",
      "mm",
      "Maximum X-axis travel distance from homing switch. Determines valid machine space for."
    ],
    "131": [
      "Y-axis maximum travel",
      "mm",
      "Maximum Y-axis travel distance from homing switch. Determines valid machine space for."
    ],
    "132": [
      "Z-axis maximum travel",
      "mm",
      "Maximum Z-axis travel distance from homing switch. Determines valid machine space for."
    ],
    "259": [
      "Ortur Flame Sensor Debug",
      "boolean",
      "Flame Sensor Debug Mode (On/OFF)"
    ],
    "260": [
      "Ortur Flame Sensor Delta",
      "integer",
      "Flame Sensor Trigger Delta Value"
    ],
    "261": [
      "Ortur Flame Sensor Count",
      "integer",
      "Flame Sensor Trigger Count Treshold"
    ],
    "262": [
      "Ortur Gshock Sensor Threshold",
      "integer",
      "Gshock Sensor Treshold"
    ],
    "263": [
      "Ortur Auto Power Off",
      "minutes",
      "Auto Power Off in Minutes"
    ],
    "264": [
      "DLC Total Duration",
      "seconds",
      "Digital Laser Control - Total Laser Duration in Seconds"
    ],
    "265": [
      "DLC Calibration Focus",
      "mm",
      "Digital Laser Control - Calibration Focus for Autofocus"
    ],
    "266": [
      "DLC Communication Rate",
      "",
      "Digital Laser Control - Communication Rate"
    ],
    "267": [
      "DLC Mode",
      "boolean",
      "Digital Laser Control- Laser-driven mode (Default PWM mode, Digital mode)"
    ],
    "268": [
      "Console Echo Debug",
      "",
      "Set Echo Debug On Console (Default 0) [Incompatible with LightBurn Versions Under 1.0.0]"
    ],
    "269": [
      "Power Source Debug",
      "",
      "Set Debug Output for Output Voltage and Current. Allows diagnostics on input power source"
    ],
    "270": [
      "Offline controller Baud Rate",
      "integer",
      "Set the Baud Rate for offline screen communication x100 (default 5120)"
    ],
    "$-Code": [
      "Setting",
      "Units",
      "Setting Description"
    ]
  },
  "ortur.GrblHal": {
    "0": [
      "Step pulse time",
      "microseconds",
      "Sets time length per step. Minimum 2 microseconds.    This needs to be reduced from the default value of 10 when max. step rates exceed approximately 80 kHz."
    ],
    "1": [
      "Step idle delay",
      "milliseconds",
      "Sets a short hold delay when stopping to let dynamics settle before disabling steppers. Value 255 keeps motors enabled."
    ],
    "2": [
      "Step pulse invert",
      "mask",
      "Inverts the step signals (active low)."
    ],
    "3": [
      "Step direction invert",
      "mask",
      "Inverts the direction signals (active low)."
    ],
    "4": [
      "Invert stepper enable pin(s)",
      "boolean",
      "Inverts the stepper driver enable signals. Most drivers uses active low enable requiring inversion.    NOTE: If the stepper drivers shares the same enable signal only X is used."
    ],
    "5": [
      "Invert limit pins",
      "boolean",
      "Inverts the axis limit input signals."
    ],
    "7": [
      "Disable spindle with zero speed",
      "boolean",
      ""
    ],
    "10": [
      "Status report options",
      "mask",
      "Specifies optional data included in status reports.  If Run substatus is enabled it may be used for simple probe protection.    NOTE: Parser state will be sent separately after the status report and only on changes."
    ],
    "11": [
      "Junction deviation",
      "millimeters",
      "Sets how fast Grbl travels through consecutive motions. Lower value slows it down."
    ],
    "12": [
      "Arc tolerance",
      "millimeters",
      "Sets the G2 and G3 arc tracing accuracy based on radial error. Beware: A very small value may effect performance."
    ],
    "13": [
      "Report in inches",
      "boolean",
      "Enables inch units when returning any position and rate value that is not a settings value."
    ],
    "14": [
      "Invert control pins",
      "mask",
      "Inverts the control signals (active low).  NOTE: Block delete Optional stop EStop and Probe connected are optional signals availability is driver dependent."
    ],
    "15": [
      "Invert coolant pins",
      "mask",
      "Inverts the coolant and mist signals (active low)."
    ],
    "16": [
      "Invert spindle signals",
      "mask",
      "Inverts the spindle on counterclockwise and PWM signals (active low)."
    ],
    "17": [
      "Pullup disable control pins",
      "mask",
      "Disable the control signals pullup resistors. Potentially enables pulldown resistor if available.  NOTE: Block delete Optional stop and EStop are optional signals availability is driver dependent."
    ],
    "18": [
      "Pullup disable limit pins",
      "mask",
      "Disable the limit signals pullup resistors. Potentially enables pulldown resistor if available."
    ],
    "20": [
      "Soft limits enable",
      "boolean",
      "Enables soft limits checks within machine travel and sets alarm when exceeded. Requires homing."
    ],
    "21": [
      "Hard limits enable",
      "boolean",
      "When enabled immediately halts motion and throws an alarm when a limit switch is triggered. In strict mode only homing is possible when a switch is engaged."
    ],
    "22": [
      "Homing cycle",
      "boolean",
      "Enables homing cycle. Requires limit switches on axes to be automatically homed. When `Enable single axis commands` is checked single axis homing can be performed by $H<axis letter> commands.    When `Allow manual` is checked axes not homed automatically ay be  manually by $H or $H<axis letter> commands.    `Override locks` is for allowing a soft reset to disable `Homing on startup required`.  NOTE: Block delete Optional stop and EStop are optional signals availability is driver dependent."
    ],
    "23": [
      "Homing direction invert",
      "mask",
      "Homing searches for a switch in the positive direction. Set axis bit to search in negative direction."
    ],
    "24": [
      "Homing locate feed rate",
      "mm/min",
      "Feed rate to slowly engage limit switch to determine its location accurately."
    ],
    "25": [
      "Homing search seek rate",
      "mm/min",
      "Seek rate to quickly find the limit switch before the slower locating phase."
    ],
    "26": [
      "Homing switch debounce delay",
      "milliseconds",
      "Sets a short delay between phases of homing cycle to let a switch debounce."
    ],
    "27": [
      "Homing switch pull-off distance",
      "mm",
      "Retract distance after triggering switch to disengage it. Homing will fail if switch isn't cleared."
    ],
    "28": [
      "G73 Retract distance",
      "mm",
      "G73 retract distance (for chip breaking drilling)."
    ],
    "29": [
      "Pulse delay",
      "microseconds",
      "Step pulse delay.    Normally leave this at 0 as there is an implicit delay on direction changes when AMASS is active."
    ],
    "30": [
      "Maximum spindle speed",
      "RPM",
      "Maximum spindle speed. Sets PWM to maximum duty cycle."
    ],
    "31": [
      "Minimum spindle speed",
      "RPM",
      "Minimum spindle speed. Sets PWM to minimum duty cycle."
    ],
    "32": [
      "Mode of operation",
      "integer",
      "Laser mode: consecutive G1/2/3 commands will not halt when spindle speed is changed.  Lathe mode: allows use of G7 G8 G96 and G97."
    ],
    "33": [
      "Spindle PWM frequency",
      "Hz",
      "Spindle PWM frequency."
    ],
    "34": [
      "Spindle PWM off value",
      "percent",
      "Spindle PWM off value in percent (duty cycle)."
    ],
    "35": [
      "Spindle PWM min value",
      "percent",
      "Spindle PWM min value in percent (duty cycle)."
    ],
    "36": [
      "Spindle PWM max value",
      "percent",
      "Spindle PWM max value in percent (duty cycle)."
    ],
    "37": [
      "Steppers deenergize",
      "mask",
      "Specifies which steppers not to disable when stopped."
    ],
    "39": [
      "Enable legacy RT commands",
      "boolean",
      "Enables normal processing of ? ! and ~ characters when part of $-setting or comment. If disabled then they are added to the input string instead."
    ],
    "40": [
      "Limit jog commands",
      "boolean",
      "Limit jog commands to machine limits for homed axes."
    ],
    "43": [
      "Homing passes",
      "integer",
      "Number of homing passes. Minimum 1 maximum 128."
    ],
    "44": [
      "Axes homing first pass",
      "mask",
      "Axes to home in first pass."
    ],
    "45": [
      "Axes homing second pass",
      "mask",
      "Axes to home in second pass."
    ],
    "46": [
      "Axes homing third pass",
      "mask",
      "Axes to home in third pass."
    ],
    "62": [
      "Sleep enable",
      "boolean",
      "Enable sleep mode."
    ],
    "63": [
      "Feed hold actions",
      "",
      "Actions taken during feed hold and on resume from feed hold."
    ],
    "64": [
      "Force init alarm",
      "boolean",
      "Starts Grbl in alarm mode after a cold reset."
    ],
    "70": [
      "Network Services",
      "",
      "Network services to enable. Consult driver documentation for availability.    NOTE: A hard reset of the controller is required after changing this setting."
    ],
    "73": [
      "WiFi Mode",
      "",
      "WiFi Mode."
    ],
    "74": [
      "WiFi Station (STA) SSID",
      "string",
      "WiFi Station (STA) SSID."
    ],
    "75": [
      "WiFi Station (STA) Password",
      "string",
      "WiFi Station (STA) Password."
    ],
    "76": [
      "WiFi Access Point (AP) SSID",
      "string",
      "WiFi Access Point (AP) SSID."
    ],
    "77": [
      "WiFi Access Point (AP) Password",
      "string",
      "WiFi Access Point (AP) Password."
    ],
    "100": [
      "X-axis travel resolution",
      "step/mm",
      "Travel resolution in steps per millimeter."
    ],
    "101": [
      "Y-axis travel resolution",
      "step/mm",
      "Travel resolution in steps per millimeter."
    ],
    "102": [
      "Z-axis travel resolution",
      "step/mm",
      "Travel resolution in steps per millimeter."
    ],
    "110": [
      "X-axis maximum rate",
      "mm/min",
      "Maximum rate. Used as G0 rapid rate."
    ],
    "111": [
      "Y-axis maximum rate",
      "mm/min",
      "Maximum rate. Used as G0 rapid rate."
    ],
    "112": [
      "Z-axis maximum rate",
      "mm/min",
      "Maximum rate. Used as G0 rapid rate."
    ],
    "120": [
      "X-axis acceleration",
      "mm/sec^2",
      "Acceleration. Used for motion planning to not exceed motor torque and lose steps."
    ],
    "121": [
      "Y-axis acceleration",
      "mm/sec^2",
      "Acceleration. Used for motion planning to not exceed motor torque and lose steps."
    ],
    "122": [
      "Z-axis acceleration",
      "mm/sec^2",
      "Acceleration. Used for motion planning to not exceed motor torque and lose steps."
    ],
    "130": [
      "X-axis maximum travel",
      "mm",
      "Maximum axis travel distance from homing switch. Determines valid machine space for soft-limits and homing search distances."
    ],
    "131": [
      "Y-axis maximum travel",
      "mm",
      "Maximum axis travel distance from homing switch. Determines valid machine space for soft-limits and homing search distances."
    ],
    "132": [
      "Z-axis maximum travel",
      "mm",
      "Maximum axis travel distance from homing switch. Determines valid machine space for soft-limits and homing search distances."
    ],
    "140": [
      "X-axis motor current",
      "mA",
      "Motor current in mA (RMS)."
    ],
    "141": [
      "Y-axis motor current",
      "mA",
      "Motor current in mA (RMS)."
    ],
    "142": [
      "Z-axis motor current",
      "mA",
      "Motor current in mA (RMS)."
    ],
    "150": [
      "X-axis microsteps",
      "steps",
      "Microsteps per fullstep."
    ],
    "151": [
      "Y-axis microsteps",
      "steps",
      "Microsteps per fullstep."
    ],
    "152": [
      "Z-axis microsteps",
      "steps",
      "Microsteps per fullstep."
    ],
    "160": [
      "X-axis backlash compensation",
      "mm",
      "Backlash distance to compensate for."
    ],
    "161": [
      "Y-axis backlash compensation",
      "mm",
      "Backlash distance to compensate for."
    ],
    "162": [
      "Z-axis backlash compensation",
      "mm",
      "Backlash distance to compensate for."
    ],
    "200": [
      "X-axis StallGuard4 fast threshold",
      "",
      "StallGuard threshold for fast (seek) homing phase."
    ],
    "201": [
      "Y-axis StallGuard4 fast threshold",
      "",
      "StallGuard threshold for fast (seek) homing phase."
    ],
    "202": [
      "Z-axis StallGuard4 fast threshold",
      "",
      "StallGuard threshold for fast (seek) homing phase."
    ],
    "210": [
      "X-axis hold current",
      "%",
      "Motor current at standstill as a percentage of full current. NOTE: if grblHAL is configured to disable motors on standstill this setting has no use."
    ],
    "211": [
      "Y-axis hold current",
      "%",
      "Motor current at standstill as a percentage of full current. NOTE: if grblHAL is configured to disable motors on standstill this setting has no use."
    ],
    "212": [
      "Z-axis hold current",
      "%",
      "Motor current at standstill as a percentage of full current. NOTE: if grblHAL is configured to disable motors on standstill this setting has no use."
    ],
    "220": [
      "X-axis StallGuard4 slow threshold",
      "",
      "StallGuard threshold for slow (feed) homing phase."
    ],
    "221": [
      "Y-axis StallGuard4 slow threshold",
      "",
      "StallGuard threshold for slow (feed) homing phase."
    ],
    "222": [
      "Z-axis StallGuard4 slow threshold",
      "",
      "StallGuard threshold for slow (feed) homing phase."
    ],
    "300": [
      "Hostname",
      "string",
      "Network hostname.    NOTE: A hard reset of the controller is required after changing this setting."
    ],
    "302": [
      "IP Address",
      "IPv4 address",
      "Static IP address.    NOTE: A hard reset of the controller is required after changing this setting."
    ],
    "303": [
      "Gateway",
      "IPv4 address",
      "Static gateway address.    NOTE: A hard reset of the controller is required after changing this setting."
    ],
    "304": [
      "Netmask",
      "IPv4 mask",
      "Static netmask.    NOTE: A hard reset of the controller is required after changing this setting."
    ],
    "305": [
      "Telnet port",
      "integer",
      "(Raw) Telnet port number listening for incoming connections.    NOTE: A hard reset of the controller is required after changing this setting."
    ],
    "306": [
      "HTTP port",
      "integer",
      "HTTP port number listening for incoming connections.    NOTE: A hard reset of the controller is required after changing this setting."
    ],
    "307": [
      "Websocket port",
      "integer",
      "Websocket port number listening for incoming connections.    NOTE: A hard reset of the controller is required after changing this setting.NOTE: WebUI requires this to be HTTP port number + 1."
    ],
    "308": [
      "FTP port",
      "integer",
      "FTP port number listening for incoming connections.    NOTE: A hard reset of the controller is required after changing this setting."
    ],
    "310": [
      "Hostname (AP)",
      "string",
      "Network hostname.    NOTE: A hard reset of the controller is required after changing this setting."
    ],
    "312": [
      "IP Address (AP)",
      "IPv4 address",
      "Static IP address.    NOTE: A hard reset of the controller is required after changing this setting."
    ],
    "313": [
      "Gateway (AP)",
      "IPv4 address",
      "Static gateway address.    NOTE: A hard reset of the controller is required after changing this setting."
    ],
    "314": [
      "Netmask (AP)",
      "IPv4 mask",
      "Static netmask.    NOTE: A hard reset of the controller is required after changing this setting."
    ],
    "330": [
      "Admin Password",
      "string",
      "Administrator password."
    ],
    "331": [
      "User Password",
      "string",
      "User password."
    ],
    "339": [
      "Sensorless homing",
      "boolean",
      "Enable sensorless homing for axis. Requires SPI controlled Trinamic drivers."
    ],
    "341": [
      "Tool change mode",
      "",
      "Normal: allows jogging for manual touch off. Set new position manually.   Manual touch off: retracts tool axis to home position for tool change use jogging or $TPW for touch off.   Manual touch off  G59.3: retracts tool axis to home position then to G59.3 position for tool change use jogging or $TPW for touch off.    Automatic touch off  G59.3: retracts tool axis to home position for tool change then to G59.3 position for automatic touch off.   All modes except Normal and Ignore M6 returns the tool (controlled point) to original position after touch off."
    ],
    "342": [
      "Tool change probing distance",
      "mm",
      "Maximum probing distance for automatic or $TPW touch off."
    ],
    "343": [
      "Tool change locate feed rate",
      "mm/min",
      "Feed rate to slowly engage tool change sensor to determine the tool offset accurately."
    ],
    "344": [
      "Tool change search seek rate",
      "mm/min",
      "Seek rate to quickly find the tool change sensor before the slower locating phase."
    ],
    "345": [
      "Tool change probe pull-off rate",
      "mm/min",
      "Pull-off rate for the retract move before the slower locating phase."
    ],
    "384": [
      "Disable G92 persistence",
      "",
      "Disables save/restore of G92 offset to non-volatile storage (NVS)."
    ],
    "600": [
      "Power log enable",
      "boolean",
      "Whether to enable the main power supply power supply debugging information."
    ],
    "601": [
      "Voltage offset",
      "v",
      "Maximum voltage offset allowed for the main power supply."
    ],
    "602": [
      "device auto poweroff time",
      "s",
      "Maximum voltage offset allowed for the main power supply."
    ],
    "603": [
      "Shock detection alarm threshold",
      "",
      "Value range: 0~1000 The Recommended value = 3 If the alarm threshold for shock detection is set to 0 the function is disabled."
    ],
    "604": [
      "Skew detection alarm threshold",
      "",
      "Value range: 0~1000 The Recommended value = 4 If the alarm threshold for skew detection is set to 0 the function is disabled."
    ],
    "605": [
      "Buzz enable",
      "boolean",
      "Enable buzz function"
    ],
    "606": [
      "Homing upon power up enable",
      "boolean",
      "Homing upon power up"
    ],
    "607": [
      "Report echo line received enable",
      "boolean",
      "Report echo line received enable."
    ],
    "608": [
      "Return Line number enable",
      "boolean",
      "Return Line number enable"
    ],
    "609": [
      "Homing switch seek pull-off distance",
      "mm",
      "Homing seek pull-off distance"
    ],
    "610": [
      "Language",
      "",
      "Set the firmware language"
    ],
    "611": [
      "Auto power on forever",
      "boolean",
      "Enable auto-poweron function"
    ],
    "612": [
      "Webui auth enable",
      "boolean",
      "Enable webui auth function"
    ],
    "622": [
      "Homing cycle",
      "boolean",
      "Enables homing cycle. Requires limit switches on axes to be automatically homed. When `Enable single axis commands` is checked single axis homing can be performed by $H<axis letter> commands.When `Allow manual` is checked axes not homed automatically may be homed manually by $H or $H<axis letter> commands.`Override locks` is for allowing a soft reset to disable `Homing on startup required`."
    ],
    "$-Code": [
      "Setting",
      "Units",
      "Setting Description"
    ]
  },
  "longer.nanoduo": {
    "0": [
      "Step pulse time",
      "microseconds",
      "Sets time length per step. Minimum 1 usec."
    ],
    "1": [
      "Step idle delay",
      "milliseconds",
      "Sets a short hold delay when stopping to let dynamics settle before disabling steppers. Value 255 keeps motors enabled with no delay."
    ],
    "2": [
      "Step pulse invert",
      "mask",
      "Inverts the step signal. Set axis bit to invert (000000YX)."
    ],
    "3": [
      "Step direction invert",
      "mask",
      "Inverts the direction signal. Set axis bit to invert (000000YX)."
    ],
    "4": [
      "Invert step enable pin",
      "boolean",
      "Inverts the stepper driver enable pin signal."
    ],
    "5": [
      "Invert limit pins",
      "boolean",
      "Inverts all of the limit input pins."
    ],
    "6": [
      "Invert probe pin",
      "boolean",
      "Inverts the probe input pin signal."
    ],
    "10": [
      "Status report options",
      "mask",
      "Alters data included in status reports."
    ],
    "11": [
      "Junction deviation",
      "millimeters",
      "Sets how fast Grbl travels through consecutive motions. Lower value slows it down."
    ],
    "12": [
      "Arc tolerance",
      "millimeters",
      "Sets the G2 and G3 arc tracing accuracy based on radial error. Beware: A very small value may affect performance."
    ],
    "13": [
      "Report in inches",
      "boolean",
      "Enables inch units when returning any position and rate value that is not a settings value."
    ],
    "20": [
      "Soft limits enable",
      "boolean",
      "Enables soft limits checks within machine travel and sets alarm when exceeded. Requires homing."
    ],
    "21": [
      "Hard limits enable",
      "boolean",
      "Enables hard limits. Immediately halts motion and throws an alarm when switch is triggered."
    ],
    "22": [
      "Homing cycle enable",
      "boolean",
      "Enables homing cycle. Requires limit switches on all axes."
    ],
    "23": [
      "Homing direction invert",
      "mask",
      "Homing searches for a switch in the positive direction. Set axis bit (000000YX) to search in negative direction."
    ],
    "24": [
      "Homing locate feed rate",
      "mm/min",
      "Feed rate to slowly engage limit switch to determine its location accurately."
    ],
    "25": [
      "Homing search seek rate",
      "mm/min",
      "Seek rate to quickly find the limit switch before the slower locating phase."
    ],
    "26": [
      "Homing switch debounce delay",
      "milliseconds",
      "Sets a short delay between phases of homing cycle to let a switch debounce."
    ],
    "27": [
      "Homing switch pull-off distance",
      "millimeters",
      "Retract distance after triggering switch to disengage it. Homing will fail if switch isn't cleared."
    ],
    "30": [
      "Maximum spindle speed",
      "RPM",
      "Maximum spindle speed. Sets PWM to 100% duty cycle."
    ],
    "31": [
      "Minimum spindle speed",
      "RPM",
      "Minimum spindle speed. Sets PWM to 0.4% or lowest duty cycle."
    ],
    "32": [
      "Laser-mode enable",
      "boolean",
      "Enables laser mode. Consecutive G1/2/3 commands will not halt when spindle speed is changed."
    ],
    "100": [
      "X-axis travel resolution",
      "step/mm",
      "X-axis travel resolution in steps per millimeter."
    ],
    "101": [
      "Y-axis travel resolution",
      "step/mm",
      "Y-axis travel resolution in steps per millimeter."
    ],
    "102": [
      "Z-axis travel resolution",
      "step/mm",
      "Z-axis travel resolution in steps per millimeter."
    ],
    "110": [
      "X-axis maximum rate",
      "mm/min",
      "X-axis maximum rate. Used as G0 rapid rate."
    ],
    "111": [
      "Y-axis maximum rate",
      "mm/min",
      "Y-axis maximum rate. Used as G0 rapid rate."
    ],
    "112": [
      "Z-axis maximum rate",
      "mm/min",
      "Z-axis maximum rate. Used as G0 rapid rate."
    ],
    "120": [
      "X-axis acceleration",
      "mm/sec^2",
      "X-axis acceleration. Used for motion planning to not exceed motor torque and lose steps."
    ],
    "121": [
      "Y-axis acceleration",
      "mm/sec^2",
      "Y-axis acceleration. Used for motion planning to not exceed motor torque and lose steps."
    ],
    "122": [
      "Z-axis acceleration",
      "mm/sec^2",
      "Z-axis acceleration. Used for motion planning to not exceed motor torque and lose steps."
    ],
    "130": [
      "X-axis maximum travel",
      "millimeters",
      "Maximum X-axis travel distance from homing switch. Determines valid machine space for soft-limits and homing search distances."
    ],
    "131": [
      "Y-axis maximum travel",
      "millimeters",
      "Maximum Y-axis travel distance from homing switch. Determines valid machine space for soft-limits and homing search distances."
    ],
    "132": [
      "Z-axis maximum travel",
      "millimeters",
      "Maximum Z-axis travel distance from homing switch. Determines valid machine space for soft-limits and homing search distances."
    ],
    "$-Code": [
      "Setting",
      "Units",
      "Setting Description"
    ]
  }
}

/** GRBL 报警码: 键为编号, 值为 [简述, 详细说明] */
export const ALARM_CODES: Record<string, Record<string, CodeEntry>> = {
  "standard": {
    "1": [
      "Hard limit",
      "Hard limit has been triggered. Machine position is likely lost due to sudden halt. Re-homing is highly recommended."
    ],
    "2": [
      "Soft limit",
      "Soft limit alarm. G-code motion target exceeds machine travel. Machine position retained. Alarm may be safely unlocked."
    ],
    "3": [
      "Abort during cycle",
      "Reset while in motion. Machine position is likely lost due to sudden halt. Re-homing is highly recommended."
    ],
    "4": [
      "Probe fail",
      "Probe fail. Probe is not in the expected initial state before starting probe cycle when G38.2 and G38.3 is not triggered and G38.4 and G38.5 is triggered."
    ],
    "5": [
      "Probe fail",
      "Probe fail. Probe did not contact the workpiece within the programmed travel for G38.2 and G38.4."
    ],
    "6": [
      "Homing fail",
      "Homing fail. The active homing cycle was reset."
    ],
    "7": [
      "Homing fail",
      "Homing fail. Safety door was opened during homing cycle."
    ],
    "8": [
      "Homing fail",
      "Homing fail. Pull off travel failed to clear limit switch. Try increasing pull-off setting or check wiring."
    ],
    "9": [
      "Homing fail",
      "Homing fail. Could not find limit switch within search distances. Try increasing max travel or decreasing pull-off distance or check wiring."
    ]
  },
  "ortur.GrblHal": {
    "1": [
      "Hard limit",
      "Hard limit has been triggered. Machine position is likely lost due to sudden halt. Re-homing is highly recommended."
    ],
    "2": [
      "Soft limit",
      "Soft limit alarm. G-code motion target exceeds machine travel. Machine position retained. Alarm may be safely unlocked."
    ],
    "3": [
      "Abort during cycle",
      "Reset while in motion. Machine position is likely lost due to sudden halt. Re-homing is highly recommended."
    ],
    "4": [
      "Probe fail",
      "Probe fail. Probe is not in the expected initial state before starting probe cycle when G38.2 and G38.3 is not triggered and G38.4 and G38.5 is triggered."
    ],
    "5": [
      "Probe fail",
      "Probe fail. Probe did not contact the workpiece within the programmed travel for G38.2 and G38.4."
    ],
    "6": [
      "Homing fail",
      "Homing fail. The active homing cycle was reset."
    ],
    "7": [
      "Homing fail",
      "Homing fail. Safety door was opened during homing cycle."
    ],
    "8": [
      "Homing fail",
      "Homing fail. Pull off travel failed to clear limit switch. Try increasing pull-off setting or check wiring."
    ],
    "9": [
      "Homing fail",
      "Homing fail. Could not find limit switch within search distances. Try increasing max travel or decreasing pull-off distance or check wiring."
    ],
    "10": [
      "EStop fail",
      "EStop asserted. Clear and reset"
    ],
    "11": [
      "Homing required",
      "Homing required. Execute homing command ($H) to continue."
    ],
    "12": [
      "Hard limit",
      "Limit switch engaged. Clear before continuing."
    ],
    "13": [
      "Probe fail",
      "Probe protection triggered. Clear before continuing."
    ],
    "14": [
      "Spindle timeout",
      "Spindle at speed timeout. Clear before continuing."
    ],
    "15": [
      "Homing fail",
      "Homing fail. Could not find second limit switch for auto squared axis within search distances. Try increasing max travel decreasing pull-off distance or check wiring."
    ],
    "16": [
      "POS fail",
      "Power on selftest (POS) failed."
    ],
    "17": [
      "Motor fault",
      "Motor fault."
    ]
  },
  "longer.nanoduo": {
    "1": [
      "Hard limit",
      "Hard limit has been triggered. Machine position is likely lost due to sudden halt. Re-homing is highly recommended."
    ],
    "2": [
      "Soft limit",
      "Soft limit alarm. G-code motion target exceeds machine travel. Machine position retained. Alarm may be safely unlocked."
    ],
    "3": [
      "Abort during cycle",
      "Reset while in motion. Machine position is likely lost due to sudden halt. Re-homing is highly recommended."
    ],
    "4": [
      "Probe fail",
      "Probe fail. Probe is not in the expected initial state before starting probe cycle when G38.2 and G38.3 is not triggered and G38.4 and G38.5 is triggered."
    ],
    "5": [
      "Probe fail",
      "Probe fail. Probe did not contact the workpiece within the programmed travel for G38.2 and G38.4."
    ],
    "6": [
      "Homing fail",
      "Homing fail. The active homing cycle was reset."
    ],
    "7": [
      "Homing fail",
      "Homing fail. Safety door was opened during homing cycle."
    ],
    "8": [
      "Homing fail",
      "Homing fail. Pull off travel failed to clear limit switch. Try increasing pull-off setting or check wiring."
    ],
    "9": [
      "Homing fail",
      "Homing fail. Could not find limit switch within search distances. Try increasing max travel or decreasing pull-off distance or check wiring."
    ],
    "10": [
      "SpindleControl",
      "Spindle Control"
    ],
    "11": [
      "MontionSensor",
      "Warning!! MOTION Sensor Triggered"
    ],
    "12": [
      "FlameSensor",
      "Warning!! FLAME Sensor Triggered"
    ],
    "13": [
      "TemperatureSensor",
      "Warning!! TEMPERATURE Sensor Triggered"
    ]
  }
}

/** GRBL 错误码: 键为编号, 值为 [简述, 详细说明] */
export const ERROR_CODES: Record<string, Record<string, CodeEntry>> = {
  "standard": {
    "1": [
      "Expected command letter",
      "G-code words consist of a letter and a value. Letter was not found."
    ],
    "2": [
      "Bad number format",
      "Missing the expected G-code word value or numeric value format is not valid."
    ],
    "3": [
      "Invalid statement",
      "Grbl '$' system command was not recognized or supported."
    ],
    "4": [
      "Value < 0",
      "Negative value received for an expected positive value."
    ],
    "5": [
      "Setting disabled",
      "Homing cycle failure. Homing is not enabled via settings."
    ],
    "6": [
      "Value < 3 usec",
      "Minimum step pulse time must be greater than 3usec."
    ],
    "7": [
      "EEPROM read fail. Using defaults",
      "An EEPROM read failed. Auto-restoring affected EEPROM to default values."
    ],
    "8": [
      "Not idle",
      "Grbl '$' command cannot be used unless Grbl is IDLE. Ensures smooth operation during a job."
    ],
    "9": [
      "G-code lock",
      "G-code commands are locked out during alarm or jog state."
    ],
    "10": [
      "Homing not enabled",
      "Soft limits cannot be enabled without homing also enabled."
    ],
    "11": [
      "Line overflow",
      "Max characters per line exceeded. Received command line was not executed."
    ],
    "12": [
      "Step rate > 30kHz",
      "Grbl '$' setting value cause the step rate to exceed the maximum supported."
    ],
    "13": [
      "Check Door",
      "Safety door detected as opened and door state initiated."
    ],
    "14": [
      "Line length exceeded",
      "Build info or startup line exceeded EEPROM line length limit. Line not stored."
    ],
    "15": [
      "Travel exceeded",
      "Jog target exceeds machine travel. Jog command has been ignored."
    ],
    "16": [
      "Invalid jog command",
      "Jog command has no '=' or contains prohibited g-code."
    ],
    "18": [
      "Emergency stop engaged",
      "Please rotate emergency stop button to release."
    ],
    "20": [
      "Unsupported command",
      "Unsupported or invalid g-code command found in block."
    ],
    "21": [
      "Modal group violation",
      "More than one g-code command from same modal group found in block."
    ],
    "22": [
      "Undefined feed rate",
      "Feed rate has not yet been set or is undefined."
    ],
    "23": [
      "Invalid gcode ID:23",
      "G-code command in block requires an integer value."
    ],
    "24": [
      "Invalid gcode ID:24",
      "More than one g-code command that requires axis words found in block."
    ],
    "25": [
      "Invalid gcode ID:25",
      "Repeated g-code word found in block."
    ],
    "26": [
      "Invalid gcode ID:26",
      "No axis words found in block for g-code command or current modal state which requires them."
    ],
    "27": [
      "Invalid gcode ID:27",
      "Line number value is invalid."
    ],
    "28": [
      "Invalid gcode ID:28",
      "G-code command is missing a required value word."
    ],
    "29": [
      "Invalid gcode ID:29",
      "G59.x work coordinate systems are not supported."
    ],
    "30": [
      "Invalid gcode ID:30",
      "G53 only allowed with G0 and G1 motion modes."
    ],
    "31": [
      "Invalid gcode ID:31",
      "Axis words found in block when no command or current modal state uses them."
    ],
    "32": [
      "Invalid gcode ID:32",
      "G2 and G3 arcs require at least one in-plane axis word."
    ],
    "33": [
      "Invalid gcode ID:33",
      "Motion command target is invalid."
    ],
    "34": [
      "Invalid gcode ID:34",
      "Arc radius value is invalid."
    ],
    "35": [
      "Invalid gcode ID:35",
      "G2 and G3 arcs require at least one in-plane offset word."
    ],
    "36": [
      "Invalid gcode ID:36",
      "Unused value words found in block."
    ],
    "37": [
      "Invalid gcode ID:37",
      "G43.1 dynamic tool length offset is not assigned to configured tool length axis."
    ]
  },
  "ortur.GrblHal": {
    "1": [
      "Expected command letter",
      "G-code words consist of a letter and a value. Letter was not found."
    ],
    "2": [
      "Bad number format",
      "Missing the expected G-code word value or numeric value format is not valid."
    ],
    "3": [
      "Invalid statement",
      "$' system command was not recognized or supported."
    ],
    "4": [
      "Value < 0",
      "Negative value received for an expected positive value."
    ],
    "5": [
      "Setting disabled",
      "Homing cycle failure. Homing is not configured via settings."
    ],
    "6": [
      "Value < 3 usec",
      "Step pulse time must be greater or equal to 2 microseconds."
    ],
    "7": [
      "EEPROM read fail. Using defaults",
      "A settings read failed. Auto-restoring affected settings to default values."
    ],
    "8": [
      "Not idle",
      "$' command cannot be used unless controller state is IDLE. Ensures smooth operation during a job."
    ],
    "9": [
      "G-code lock",
      "G-code commands are locked out during alarm or jog state."
    ],
    "10": [
      "Homing not enabled",
      "Soft limits cannot be enabled without homing also enabled."
    ],
    "11": [
      "Line overflow",
      "Max characters per line exceeded. Received command line was not executed."
    ],
    "12": [
      "Step rate > 30kHz",
      "$' setting value cause the step rate to exceed the maximum supported."
    ],
    "13": [
      "Check Door",
      "Safety door detected as opened and door state initiated."
    ],
    "14": [
      "Line length exceeded",
      "Build info or startup line exceeded line length limit. Line not stored."
    ],
    "15": [
      "Travel exceeded",
      "Jog target exceeds machine travel. Jog command has been ignored."
    ],
    "16": [
      "Invalid jog command",
      "Jog command has no '=' or contains prohibited g-code."
    ],
    "17": [
      "PWM is needed",
      "Laser mode requires PWM output."
    ],
    "18": [
      "Reset asserted",
      "Reset asserted"
    ],
    "19": [
      "Non positive value",
      "Non positive value"
    ],
    "20": [
      "Unsupported command",
      "Unsupported or invalid g-code command found in block."
    ],
    "21": [
      "Modal group violation",
      "More than one g-code command from same modal group found in block."
    ],
    "22": [
      "Undefined feed rate",
      "Feed rate has not yet been set or is undefined."
    ],
    "23": [
      "Invalid gcode ID:23",
      "G-code command in block requires an integer value."
    ],
    "24": [
      "Invalid gcode ID:24",
      "More than one g-code command that requires axis words found in block."
    ],
    "25": [
      "Invalid gcode ID:25",
      "Repeated g-code word found in block."
    ],
    "26": [
      "Invalid gcode ID:26",
      "No axis words found in block for g-code command or current modal state which requires them."
    ],
    "27": [
      "Invalid gcode ID:27",
      "Line number value is invalid."
    ],
    "28": [
      "Invalid gcode ID:28",
      "G-code command is missing a required value word."
    ],
    "29": [
      "Invalid gcode ID:29",
      "G59.x work coordinate systems are not supported."
    ],
    "30": [
      "Invalid gcode ID:30",
      "G53 only allowed with G0 and G1 motion modes."
    ],
    "31": [
      "Invalid gcode ID:31",
      "Axis words found in block when no command or current modal state uses them."
    ],
    "32": [
      "Invalid gcode ID:32",
      "G2 and G3 arcs require at least one in-plane axis word."
    ],
    "33": [
      "Invalid gcode ID:33",
      "Motion command target is invalid."
    ],
    "34": [
      "Invalid gcode ID:34",
      "Arc radius value is invalid."
    ],
    "35": [
      "Invalid gcode ID:35",
      "G2 and G3 arcs require at least one in-plane offset word."
    ],
    "36": [
      "Invalid gcode ID:36",
      "Unused value words found in block."
    ],
    "37": [
      "Invalid gcode ID:37",
      "G43.1 dynamic tool length offset is not assigned to configured tool length axis."
    ],
    "38": [
      "Invalid gcode ID:38",
      "Tool number greater than max supported value or undefined tool selected."
    ],
    "39": [
      "Invalid gcode ID:39",
      "Value out of range."
    ],
    "40": [
      "Invalid gcode ID:40",
      "G-code command not allowed when tool change is pending."
    ],
    "41": [
      "Invalid gcode ID:41",
      "Spindle not running when motion commanded in CSS or spindle sync mode."
    ],
    "42": [
      "Invalid gcode ID:42",
      "Plane must be ZX for threading."
    ],
    "43": [
      "Invalid gcode ID:43",
      "Max. feed rate exceeded."
    ],
    "44": [
      "Invalid gcode ID:44",
      "RPM out of range."
    ],
    "45": [
      "Invalid gcode ID:45",
      "Only homing is allowed when a limit switch is engaged."
    ],
    "46": [
      "Invalid gcode ID:46",
      "Home machine to continue."
    ],
    "47": [
      "Invalid gcode ID:47",
      "ATC: current tool is not set. Set current tool with M61."
    ],
    "48": [
      "Invalid gcode ID:48",
      "Value word conflict."
    ],
    "49": [
      "POST Failed",
      "Power on self test failed. A hard reset is required."
    ],
    "50": [
      "Emergency stop",
      "Emergency stop active."
    ],
    "51": [
      "Motor fault",
      "Motor fault."
    ],
    "52": [
      "Value out of range",
      "Setting value is out of range."
    ],
    "53": [
      "Invalid gcode ID:53",
      "Setting is not available  possibly due to limited driver support."
    ],
    "54": [
      "Invalid gcode ID:54",
      "Retract position is less than drill depth."
    ],
    "60": [
      "SD Error 60",
      "SD Card mount failed."
    ],
    "61": [
      "SD Error 61",
      "SD Card file open/read failed."
    ],
    "62": [
      "SD Error 62",
      "SD Card directory listing failed."
    ],
    "63": [
      "SD Error 63",
      "SD Card directory not found."
    ],
    "64": [
      "SD Error 64",
      "SD Card file empty."
    ],
    "65": [
      "Function error",
      "function is error."
    ],
    "90": [
      "WiFi error",
      "WiFi error."
    ]
  },
  "longer.nanoduo": {
    "1": [
      "Expected GCodecommand letter",
      "Expected GCodecommand letter"
    ],
    "2": [
      "Bad GCode number format",
      "Bad GCode number format"
    ],
    "3": [
      "Invalid $ statement",
      "Invalid $ statement"
    ],
    "4": [
      "Negative value",
      "Negative value"
    ],
    "5": [
      "Setting disabled",
      "Setting disabled"
    ],
    "6": [
      "Step pulse too short",
      "Step pulse too short"
    ],
    "7": [
      "Failed to read settings",
      "Failed to read settings"
    ],
    "8": [
      "Command requires idle state",
      "Command requires idle state"
    ],
    "9": [
      "GCode cannot be executed in lock or alarm state",
      "GCode cannot be executed in lock or alarm state"
    ],
    "10": [
      "Soft limit error",
      "Soft limit error"
    ],
    "11": [
      "Line too long",
      "Line too long"
    ],
    "12": [
      "Max step rate exceeded",
      "Max step rate exceeded"
    ],
    "13": [
      "Check door",
      "Check door"
    ],
    "14": [
      "Startup line too long",
      "Startup line too long"
    ],
    "15": [
      "Max travel exceeded during jog",
      "Max travel exceeded during jog"
    ],
    "16": [
      "Invalid jog command",
      "Invalid jog command"
    ],
    "17": [
      "Laser mode requires PWM output",
      "Laser mode requires PWM output"
    ],
    "18": [
      "No Homing/Cycle defined in settings",
      "No Homing/Cycle defined in settings"
    ],
    "19": [
      "Unsupported GCode command",
      "Unsupported GCode command"
    ],
    "20": [
      "Gcode modal group violation",
      "Gcode modal group violation"
    ],
    "21": [
      "Gcode undefined feed rate",
      "Gcode undefined feed rate"
    ],
    "22": [
      "Gcode command value not integer",
      "Gcode command value not integer"
    ],
    "23": [
      "Gcode axis command conflict",
      "Gcode axis command conflict"
    ],
    "24": [
      "Gcode word repeated",
      "Gcode word repeated"
    ],
    "25": [
      "Gcode no axis words",
      "Gcode no axis words"
    ],
    "26": [
      "Gcode invalid line number",
      "Gcode invalid line number"
    ],
    "27": [
      "Gcode value word missing",
      "Gcode value word missing"
    ],
    "28": [
      "Gcode unsupported coordinate system",
      "Gcode unsupported coordinate system"
    ],
    "29": [
      "Gcode G53 invalid motion mode",
      "Gcode G53 invalid motion mode"
    ],
    "30": [
      "Gcode extra axis words",
      "Gcode extra axis words"
    ],
    "31": [
      "Gcode no axis words in plane",
      "Gcode no axis words in plane"
    ],
    "32": [
      "Gcode invalid target",
      "Gcode invalid target"
    ],
    "33": [
      "Gcode arc radius error",
      "Gcode arc radius error"
    ],
    "34": [
      "Gcode no offsets in plane",
      "Gcode no offsets in plane"
    ],
    "35": [
      "Gcode unused words",
      "Gcode unused words"
    ],
    "36": [
      "Gcode G43 dynamic axis error",
      "Gcode G43 dynamic axis error"
    ],
    "37": [
      "Gcode max value exceeded",
      "Gcode max value exceeded"
    ],
    "38": [
      "P param max exceeded",
      "P param max exceeded"
    ],
    "39": [
      "Failed to mount device",
      "Failed to mount device"
    ],
    "40": [
      "Failed to read",
      "Failed to read"
    ],
    "41": [
      "Failed to open directory",
      "Failed to open directory"
    ],
    "42": [
      "Directory not found",
      "Directory not found"
    ],
    "43": [
      "File empty",
      "File empty"
    ],
    "44": [
      "File not found",
      "File not found"
    ],
    "45": [
      "Failed to open file",
      "Failed to open file"
    ],
    "46": [
      "Device is busy",
      "Device is busy"
    ],
    "47": [
      "Failed to delete directory",
      "Failed to delete directory"
    ],
    "48": [
      "Failed to delete file",
      "Failed to delete file"
    ],
    "49": [
      "Bluetooth failed to start",
      "Bluetooth failed to start"
    ],
    "50": [
      "WiFi failed to start",
      "WiFi failed to start"
    ],
    "51": [
      "Number out of range for setting",
      "Number out of range for setting"
    ],
    "52": [
      "Invalid value for setting",
      "Invalid value for setting"
    ],
    "53": [
      "Failed to send message",
      "Failed to send message"
    ],
    "54": [
      "Failed to store setting",
      "Failed to store setting"
    ],
    "55": [
      "Failed to get setting status",
      "Failed to get setting status"
    ],
    "56": [
      "Authentication failed!",
      "Authentication failed!"
    ],
    "57": [
      "Another interface is busy",
      "Another interface is busy"
    ],
    "58": [
      "Jog Cancelled",
      "Jog Cancelled"
    ],
    "59": [
      "Zip file failed to open",
      "Zip file failed to open"
    ],
    "60": [
      "Failed to get information from zip file",
      "Failed to get information from zip file"
    ],
    "61": [
      "The upgraded firmware was not found",
      "The upgraded firmware was not found"
    ],
    "62": [
      "Upgrade failed",
      "Upgrade failed"
    ]
  }
}
