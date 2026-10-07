// 由 tools/port/gen-csvdata.mjs 从 src/core/grbl/csvData.ts 生成，请勿手改
// source: src/core/grbl/csvData.ts
// source-sha256: 260c18d58dba9cd82e9c49c827a1feadc9a4da88499068f6749fec7e5e6f2376
// content-sha256: 45f2d2070b9337862b94d2718035429d7db3357884d4339c819a09e5c525c926
// source-bytes: 82541  source-lines: 2961
// 重新生成: node tools/port/gen-csvdata.mjs    （输出为确定性结果，重复执行 sha256 不变）

package com.lasergrbl.core.grbl

/**
 * GRBL 参数 / 报警码 / 错误码对照表，由 TypeScript 版本 (`src/core/grbl/csvData.ts`) 机械移植。
 *
 * 结构: 表 -> 固件族 (如 "v1.1" / "v0.9" / "ortur.*") -> 编号字符串 -> 字符串列表。
 * 所有 Map/List 均为 insertion-ordered (`linkedMapOf` / `listOf`)，
 * 迭代顺序与 TypeScript 对象字面量书写顺序完全一致。
 */
object CsvData {
    /** 源文件 `src/core/grbl/csvData.ts` 的 sha256。 */
    const val SOURCE_SHA256: String = "260c18d58dba9cd82e9c49c827a1feadc9a4da88499068f6749fec7e5e6f2376"

    /** 三张表内容的规范化摘要 (sha256)，用于运行时/CI 自检。 */
    const val CONTENT_SHA256: String = "45f2d2070b9337862b94d2718035429d7db3357884d4339c819a09e5c525c926"

    // SETTING_CODES: 8 个固件族, 426 条记录
    val SETTING_CODES: Map<String, Map<String, List<String>>> = linkedMapOf<String, Map<String, List<String>>>(
        // v0.8 (24)
        "v0.8" to linkedMapOf<String, List<String>>(
            "0" to listOf("X-axis travel resolution", "step/mm", "X-axis travel resolution in steps per millimeter."),
            "1" to listOf("Y-axis travel resolution", "step/mm", "Y-axis travel resolution in steps per millimeter."),
            "2" to listOf("Z-axis travel resolution", "step/mm", "Z-axis travel resolution in steps per millimeter."),
            "3" to listOf("Step pulse time", "microseconds", "Sets time length per step."),
            "4" to listOf(
                "Default feed rates",
                "mm/min",
                "This setting sets the default seek rates (G0) after Grbl powers on and initializes"
            ),
            "5" to listOf(
                "Default seek rates",
                "mm/min",
                "This setting sets the default feed rates(G1 G2 G3) after Grbl powers on and initializes"
            ),
            "6" to listOf("Step port invert mask", "int:00000000", "Inverts the step signal."),
            "7" to listOf(
                "step idle delay",
                "msec",
                "Sets a short hold delay when stopping to let dynamics settle before disabling steppers. Value 255 keeps motors enabled with no delay."
            ),
            "8" to listOf(
                "acceleration",
                "mm/sec^2",
                "Used for motion planning to not exceed motor torque and lose steps."
            ),
            "9" to listOf(
                "junction deviation",
                "mm",
                "Sets how fast Grbl travels through consecutive motions. Lower value slows it down."
            ),
            "10" to listOf("arc", "mm/segment", ""),
            "11" to listOf(
                "n-arc correction",
                "int",
                "This is an advanced setting that shouldn't be changed unless there are circumstances that you need to."
            ),
            "12" to listOf(
                "n-decimals",
                "int",
                "Set how many decimal places all of the floating point values Grbl reports."
            ),
            "13" to listOf(
                "report inches",
                "bool",
                "Enables inch units when returning any position and rate value that is not a settings value."
            ),
            "14" to listOf("auto start", "bool", ""),
            "15" to listOf("invert step enable", "bool", ""),
            "16" to listOf("hard limits", "bool", ""),
            "17" to listOf("homing cycle", "bool", ""),
            "18" to listOf("homing dir invert mask", "int:00000000", ""),
            "19" to listOf("homing feed", "mm/min", ""),
            "20" to listOf("homing seek", "mm/min", ""),
            "21" to listOf("homing debounce", "msec", ""),
            "22" to listOf("homing pull-off", "mm", ""),
            "\$-Code" to listOf("Setting", "Units", "Setting Description")
        ),
        // v0.9 (32)
        "v0.9" to linkedMapOf<String, List<String>>(
            "0" to listOf("Step pulse time", "microseconds", "Sets time length per step. Minimum 3usec."),
            "1" to listOf(
                "Step idle delay",
                "milliseconds",
                "Sets a short hold delay when stopping to let dynamics settle before disabling steppers. Value 255 keeps motors enabled with no delay."
            ),
            "2" to listOf("Step pulse invert", "mask", "Inverts the step signal. Set axis bit to invert (00000ZYX)."),
            "3" to listOf(
                "Step direction invert",
                "mask",
                "Inverts the direction signal. Set axis bit to invert (00000ZYX)."
            ),
            "4" to listOf("Invert step enable pin", "boolean", "Inverts the stepper driver enable pin signal."),
            "5" to listOf("Invert limit pins", "boolean", "Inverts the all of the limit input pins."),
            "6" to listOf("Invert probe pin", "boolean", "Inverts the probe input pin signal."),
            "10" to listOf("Status report options", "mask", "Alters data included in status reports."),
            "11" to listOf(
                "Junction deviation",
                "millimeters",
                "Sets how fast Grbl travels through consecutive motions. Lower value slows it down."
            ),
            "12" to listOf(
                "Arc tolerance",
                "millimeters",
                "Sets the G2 and G3 arc tracing accuracy based on radial error. Beware: A very small value may effect performance."
            ),
            "13" to listOf(
                "Report in inches",
                "boolean",
                "Enables inch units when returning any position and rate value that is not a settings value."
            ),
            "20" to listOf(
                "Soft limits enable",
                "boolean",
                "Enables soft limits checks within machine travel and sets alarm when exceeded. Requires homing."
            ),
            "21" to listOf(
                "Hard limits enable",
                "boolean",
                "Enables hard limits. Immediately halts motion and throws an alarm when switch is triggered."
            ),
            "22" to listOf("Homing cycle enable", "boolean", "Enables homing cycle. Requires limit switches on all axes."),
            "23" to listOf(
                "Homing direction invert",
                "mask",
                "Homing searches for a switch in the positive direction. Set axis bit (00000ZYX) to search in negative direction."
            ),
            "24" to listOf(
                "Homing locate feed rate",
                "mm/min",
                "Feed rate to slowly engage limit switch to determine its location accurately."
            ),
            "25" to listOf(
                "Homing search seek rate",
                "mm/min",
                "Seek rate to quickly find the limit switch before the slower locating phase."
            ),
            "26" to listOf(
                "Homing switch debounce delay",
                "milliseconds",
                "Sets a short delay between phases of homing cycle to let a switch debounce."
            ),
            "27" to listOf(
                "Homing switch pull-off distance",
                "millimeters",
                "Retract distance after triggering switch to disengage it. Homing will fail if switch isn't cleared."
            ),
            "100" to listOf("X-axis travel resolution", "step/mm", "X-axis travel resolution in steps per millimeter."),
            "101" to listOf("Y-axis travel resolution", "step/mm", "Y-axis travel resolution in steps per millimeter."),
            "102" to listOf("Z-axis travel resolution", "step/mm", "Z-axis travel resolution in steps per millimeter."),
            "110" to listOf("X-axis maximum rate", "mm/min", "X-axis maximum rate. Used as G0 rapid rate."),
            "111" to listOf("Y-axis maximum rate", "mm/min", "Y-axis maximum rate. Used as G0 rapid rate."),
            "112" to listOf("Z-axis maximum rate", "mm/min", "Z-axis maximum rate. Used as G0 rapid rate."),
            "120" to listOf(
                "X-axis acceleration",
                "mm/sec^2",
                "X-axis acceleration. Used for motion planning to not exceed motor torque and lose steps."
            ),
            "121" to listOf(
                "Y-axis acceleration",
                "mm/sec^2",
                "Y-axis acceleration. Used for motion planning to not exceed motor torque and lose steps."
            ),
            "122" to listOf(
                "Z-axis acceleration",
                "mm/sec^2",
                "Z-axis acceleration. Used for motion planning to not exceed motor torque and lose steps."
            ),
            "130" to listOf(
                "X-axis maximum travel",
                "millimeters",
                "Maximum X-axis travel distance from homing switch. Determines valid machine space for soft-limits and homing search distances."
            ),
            "131" to listOf(
                "Y-axis maximum travel",
                "millimeters",
                "Maximum Y-axis travel distance from homing switch. Determines valid machine space for soft-limits and homing search distances."
            ),
            "132" to listOf(
                "Z-axis maximum travel",
                "millimeters",
                "Maximum Z-axis travel distance from homing switch. Determines valid machine space for soft-limits and homing search distances."
            ),
            "\$-Code" to listOf("Setting", "Units", "Setting Description")
        ),
        // v1.1 (35)
        "v1.1" to linkedMapOf<String, List<String>>(
            "0" to listOf("Step pulse time", "microseconds", "Sets time length per step. Minimum 3usec."),
            "1" to listOf(
                "Step idle delay",
                "milliseconds",
                "Sets a short hold delay when stopping to let dynamics settle before disabling steppers. Value 255 keeps motors enabled with no delay."
            ),
            "2" to listOf("Step pulse invert", "mask", "Inverts the step signal. Set axis bit to invert (00000ZYX)."),
            "3" to listOf(
                "Step direction invert",
                "mask",
                "Inverts the direction signal. Set axis bit to invert (00000ZYX)."
            ),
            "4" to listOf("Invert step enable pin", "boolean", "Inverts the stepper driver enable pin signal."),
            "5" to listOf("Invert limit pins", "boolean", "Inverts all of the limit input pins."),
            "6" to listOf("Invert probe pin", "boolean", "Inverts the probe input pin signal."),
            "10" to listOf("Status report options", "mask", "Alters data included in status reports."),
            "11" to listOf(
                "Junction deviation",
                "millimeters",
                "Sets how fast Grbl travels through consecutive motions. Lower value slows it down."
            ),
            "12" to listOf(
                "Arc tolerance",
                "millimeters",
                "Sets the G2 and G3 arc tracing accuracy based on radial error. Beware: A very small value may effect performance."
            ),
            "13" to listOf(
                "Report in inches",
                "boolean",
                "Enables inch units when returning any position and rate value that is not a settings value."
            ),
            "20" to listOf(
                "Soft limits enable",
                "boolean",
                "Enables soft limits checks within machine travel and sets alarm when exceeded. Requires homing."
            ),
            "21" to listOf(
                "Hard limits enable",
                "boolean",
                "Enables hard limits. Immediately halts motion and throws an alarm when switch is triggered."
            ),
            "22" to listOf("Homing cycle enable", "boolean", "Enables homing cycle. Requires limit switches on all axes."),
            "23" to listOf(
                "Homing direction invert",
                "mask",
                "Homing searches for a switch in the positive direction. Set axis bit (00000ZYX) to search in negative direction."
            ),
            "24" to listOf(
                "Homing locate feed rate",
                "mm/min",
                "Feed rate to slowly engage limit switch to determine its location accurately."
            ),
            "25" to listOf(
                "Homing search seek rate",
                "mm/min",
                "Seek rate to quickly find the limit switch before the slower locating phase."
            ),
            "26" to listOf(
                "Homing switch debounce delay",
                "milliseconds",
                "Sets a short delay between phases of homing cycle to let a switch debounce."
            ),
            "27" to listOf(
                "Homing switch pull-off distance",
                "millimeters",
                "Retract distance after triggering switch to disengage it. Homing will fail if switch isn't cleared."
            ),
            "30" to listOf("Maximum spindle speed", "RPM", "Maximum spindle speed. Sets PWM to 100% duty cycle."),
            "31" to listOf("Minimum spindle speed", "RPM", "Minimum spindle speed. Sets PWM to 0.4% or lowest duty cycle."),
            "32" to listOf(
                "Laser-mode enable",
                "boolean",
                "Enables laser mode. Consecutive G1/2/3 commands will not halt when spindle speed is changed."
            ),
            "100" to listOf("X-axis travel resolution", "step/mm", "X-axis travel resolution in steps per millimeter."),
            "101" to listOf("Y-axis travel resolution", "step/mm", "Y-axis travel resolution in steps per millimeter."),
            "102" to listOf("Z-axis travel resolution", "step/mm", "Z-axis travel resolution in steps per millimeter."),
            "110" to listOf("X-axis maximum rate", "mm/min", "X-axis maximum rate. Used as G0 rapid rate."),
            "111" to listOf("Y-axis maximum rate", "mm/min", "Y-axis maximum rate. Used as G0 rapid rate."),
            "112" to listOf("Z-axis maximum rate", "mm/min", "Z-axis maximum rate. Used as G0 rapid rate."),
            "120" to listOf(
                "X-axis acceleration",
                "mm/sec^2",
                "X-axis acceleration. Used for motion planning to not exceed motor torque and lose steps."
            ),
            "121" to listOf(
                "Y-axis acceleration",
                "mm/sec^2",
                "Y-axis acceleration. Used for motion planning to not exceed motor torque and lose steps."
            ),
            "122" to listOf(
                "Z-axis acceleration",
                "mm/sec^2",
                "Z-axis acceleration. Used for motion planning to not exceed motor torque and lose steps."
            ),
            "130" to listOf(
                "X-axis maximum travel",
                "millimeters",
                "Maximum X-axis travel distance from homing switch. Determines valid machine space for soft-limits and homing search distances."
            ),
            "131" to listOf(
                "Y-axis maximum travel",
                "millimeters",
                "Maximum Y-axis travel distance from homing switch. Determines valid machine space for soft-limits and homing search distances."
            ),
            "132" to listOf(
                "Z-axis maximum travel",
                "millimeters",
                "Maximum Z-axis travel distance from homing switch. Determines valid machine space for soft-limits and homing search distances."
            ),
            "\$-Code" to listOf("Setting", "Units", "Setting Description")
        ),
        // ortur.v1.4.x (36)
        "ortur.v1.4.x" to linkedMapOf<String, List<String>>(
            "0" to listOf("Step pulse time", "microseconds", "Sets time length per step. Minimum 3usec."),
            "1" to listOf(
                "Step idle delay",
                "milliseconds",
                "Sets a short hold delay when stopping to let dynamics settle before disabling steppers. Value 255 keeps motors enabled with no delay."
            ),
            "2" to listOf("Step pulse invert", "mask", "Inverts the step signal. Set axis bit to invert (00000ZYX)."),
            "3" to listOf(
                "Step direction invert",
                "mask",
                "Inverts the direction signal. Set axis bit to invert (00000ZYX)."
            ),
            "4" to listOf("Invert step enable pin", "boolean", "Inverts the stepper driver enable pin signal."),
            "5" to listOf("Invert limit pins", "boolean", "Inverts the all of the limit input pins."),
            "6" to listOf("Invert probe pin", "boolean", "Inverts the probe input pin signal."),
            "10" to listOf("Status report options", "mask", "Alters data included in status reports."),
            "11" to listOf(
                "Junction deviation",
                "millimeters",
                "Sets how fast Grbl travels through consecutive motions. Lower value slows it down."
            ),
            "12" to listOf(
                "Arc tolerance",
                "millimeters",
                "Sets the G2 and G3 arc tracing accuracy based on radial error. Beware: A very small value may effect performance."
            ),
            "13" to listOf(
                "Report in inches",
                "boolean",
                "Enables inch units when returning any position and rate value that is not a settings value."
            ),
            "20" to listOf(
                "Soft limits enable",
                "boolean",
                "Enables soft limits checks within machine travel and sets alarm when exceeded. Requires homing."
            ),
            "21" to listOf(
                "Hard limits enable",
                "boolean",
                "Enables hard limits. Immediately halts motion and throws an alarm when switch is triggered."
            ),
            "22" to listOf("Homing cycle enable", "boolean", "Enables homing cycle. Requires limit switches on all axes."),
            "23" to listOf(
                "Homing direction invert",
                "mask",
                "Homing searches for a switch in the positive direction. Set axis bit (00000ZYX) to search in negative direction."
            ),
            "24" to listOf(
                "Homing locate feed rate",
                "mm/min",
                "Feed rate to slowly engage limit switch to determine its location accurately."
            ),
            "25" to listOf(
                "Homing search seek rate",
                "mm/min",
                "Seek rate to quickly find the limit switch before the slower locating phase."
            ),
            "26" to listOf(
                "Homing switch debounce delay",
                "milliseconds",
                "Sets a short delay between phases of homing cycle to let a switch debounce."
            ),
            "27" to listOf(
                "Homing switch pull-off distance",
                "millimeters",
                "Retract distance after triggering switch to disengage it. Homing will fail if switch isn't cleared."
            ),
            "30" to listOf("Maximum spindle speed", "RPM", "Maximum spindle speed. Sets PWM to 100% duty cycle."),
            "31" to listOf("Minimum spindle speed", "RPM", "Minimum spindle speed. Sets PWM to 0.4% or lowest duty cycle."),
            "32" to listOf(
                "Laser-mode enable",
                "boolean",
                "Enables laser mode. Consecutive G1/2/3 commands will not halt when spindle speed is changed."
            ),
            "33" to listOf("G-sensor threshold", "double", "Safety threshold for shock and movement detection"),
            "100" to listOf("X-axis travel resolution", "step/mm", "X-axis travel resolution in steps per millimeter."),
            "101" to listOf("Y-axis travel resolution", "step/mm", "Y-axis travel resolution in steps per millimeter."),
            "102" to listOf("Z-axis travel resolution", "step/mm", "Z-axis travel resolution in steps per millimeter."),
            "110" to listOf("X-axis maximum rate", "mm/min", "X-axis maximum rate. Used as G0 rapid rate."),
            "111" to listOf("Y-axis maximum rate", "mm/min", "Y-axis maximum rate. Used as G0 rapid rate."),
            "112" to listOf("Z-axis maximum rate", "mm/min", "Z-axis maximum rate. Used as G0 rapid rate."),
            "120" to listOf(
                "X-axis acceleration",
                "mm/sec^2",
                "X-axis acceleration. Used for motion planning to not exceed motor torque and lose steps."
            ),
            "121" to listOf(
                "Y-axis acceleration",
                "mm/sec^2",
                "Y-axis acceleration. Used for motion planning to not exceed motor torque and lose steps."
            ),
            "122" to listOf(
                "Z-axis acceleration",
                "mm/sec^2",
                "Z-axis acceleration. Used for motion planning to not exceed motor torque and lose steps."
            ),
            "130" to listOf(
                "X-axis maximum travel",
                "millimeters",
                "Maximum X-axis travel distance from homing switch. Determines valid machine space for soft-limits and homing search distances."
            ),
            "131" to listOf(
                "Y-axis maximum travel",
                "millimeters",
                "Maximum Y-axis travel distance from homing switch. Determines valid machine space for soft-limits and homing search distances."
            ),
            "132" to listOf(
                "Z-axis maximum travel",
                "millimeters",
                "Maximum Z-axis travel distance from homing switch. Determines valid machine space for soft-limits and homing search distances."
            ),
            "\$-Code" to listOf("Setting", "Units", "Setting Description")
        ),
        // ortur.v1.5.x (71)
        "ortur.v1.5.x" to linkedMapOf<String, List<String>>(
            "0" to listOf("Step pulse time", "microseconds", "Sets time length per step. Minimum 3 microseconds."),
            "1" to listOf(
                "Step idle delay",
                "milliseconds",
                "Sets a short hold delay when stopping to let dynamics settle before disabling steppers. Value 255 keeps motors enabled."
            ),
            "2" to listOf("Step pulse invert", "mask", "Inverts the step signals (active low)."),
            "3" to listOf("Step direction invert", "mask", "Inverts the direction signals (active low)."),
            "4" to listOf(
                "Invert step enable pin",
                "boolean",
                "Inverts the stepper driver enable signals (active low). If the stepper drivers shares the same enable signal only X is used."
            ),
            "5" to listOf("Invert limit pins", "mask", "Inverts the axis limit input signals."),
            "6" to listOf("Invert probe pin", "boolean", "Inverts the probe input pin signal."),
            "10" to listOf("Status report options", "mask", "Specifies optional data included in status reports."),
            "11" to listOf(
                "Junction deviation",
                "mm",
                "Sets how fast Grbl travels through consecutive motions. Lower value slows it down."
            ),
            "12" to listOf(
                "Arc tolerance",
                "mm",
                "Sets the G2 and G3 arc tracing accuracy based on radial error. Beware: A very small value may effect performance."
            ),
            "13" to listOf(
                "Report in inches",
                "boolean",
                "Enables inch units when returning any position and rate value that is not a settings value."
            ),
            "14" to listOf("Invert control pins", "mask", "Inverts the control signals (active low)."),
            "15" to listOf("Invert coolant pins", "mask", "Inverts the coolant and mist signals (active low)."),
            "16" to listOf(
                "Invert spindle signals",
                "mask",
                "Inverts the spindle on counterclockwise and PWM signals (active low)."
            ),
            "17" to listOf(
                "Pullup disable control pins",
                "mask",
                "Disable the control signals pullup resistors. Potentially enables pulldown resistor if available."
            ),
            "18" to listOf(
                "Pullup disable limit pins",
                "mask",
                "Disable the limit signals pullup resistors. Potentially enables pulldown resistor if available."
            ),
            "19" to listOf(
                "Pullup disable probe pin",
                "boolean",
                "Disable the probe signal pullup resistor. Potentially enables pulldown resistor if available."
            ),
            "20" to listOf(
                "Soft limits enable",
                "boolean",
                "Enables soft limits checks within machine travel and sets alarm when exceeded. Requires homing."
            ),
            "21" to listOf(
                "Hard limits enable",
                "mask",
                "When enabled immediately halts motion and throws an alarm when switch is triggered. In strict mode only homing is possible after switch is triggered."
            ),
            "22" to listOf("Homing cycle enable", "boolean", "Enables homing cycle. Requires limit switches on all axes."),
            "23" to listOf(
                "Homing direction invert",
                "mask",
                "Homing searches for a switch in the positive direction. Set axis bit to search in negative direction."
            ),
            "24" to listOf(
                "Homing locate feed rate",
                "mm/min",
                "Feed rate to slowly engage limit switch to determine its location accurately."
            ),
            "25" to listOf(
                "Homing search seek rate",
                "mm/min",
                "Seek rate to quickly find the limit switch before the slower locating phase."
            ),
            "26" to listOf(
                "Homing switch debounce delay",
                "milliseconds",
                "Sets a short delay between phases of homing cycle to let a switch debounce."
            ),
            "27" to listOf(
                "Homing switch pull off distance",
                "mm",
                "Retract distance after triggering switch to disengage it. Homing will fail if switch isn't cleared."
            ),
            "28" to listOf("G73 Retract distance", "mm", "G73 retract distance (for chip breaking drilling)."),
            "29" to listOf("Pulse delay", "microseconds", "Step pulse delay."),
            "30" to listOf("Maximum spindle speed", "RPM", "Maximum spindle speed. Sets PWM to maximum duty cycle."),
            "31" to listOf("Minimum spindle speed", "RPM", "Minimum spindle speed. Sets PWM to minimum duty cycle."),
            "32" to listOf(
                "Mode of operation",
                "integer",
                "Laser mode: consecutive G1/2/3 commands will not halt when spindle speed is changed. Lathe mode: allows use of G7/G8/G96 and G97."
            ),
            "33" to listOf("PWM frequency", "Hz", "PWM frequency."),
            "34" to listOf("PWM off value", "percent", "PWM off value in percent (duty cycle)."),
            "35" to listOf("PWM min value", "percent", "PWM min value in percent (duty cycle)."),
            "36" to listOf("PWM max value", "percent", "PWM max value in percent (duty cycle)."),
            "37" to listOf("Steppers deenergize", "mask", "Specifies which steppers not to disable when stopped."),
            "39" to listOf(
                "Enable legacy RT commands",
                "boolean",
                "Enables \"normal\" processing of ? ! and ~ characters when part of \$setting or comment. If disabled then they are added to the input string instead."
            ),
            "40" to listOf("Limit jog commands", "boolean", "Limit jog commands to machine limits for homed axes."),
            "41" to listOf("Safety Door", "boolean", "Enable Safety Door."),
            "42" to listOf("Safety Door", "mask", "Define which axis that performs the parking motion."),
            "43" to listOf("Homing passes", "integer", "Number of homing passes. Minimum 1 maximum 128."),
            "44" to listOf("Axes homing", "mask", "Axes to home in first pass."),
            "45" to listOf("Axes homing", "mask", "Axes to home in second pass."),
            "46" to listOf("Axes homing", "mask", "Axes to home in third pass."),
            "56" to listOf("Safety Door", "boolean", "Spindle pull out and plunge distance in mm. Incremental distance."),
            "57" to listOf("Safety Door", "integer", "Pull out/plunge slow feed rate in mm/min."),
            "58" to listOf("Safety Door", "integer", "Parking axis target. In mm  as machine coordinate"),
            "59" to listOf("Safety Door", "integer", "Parking fast rate after pull out in mm/min."),
            "60" to listOf("Restore overrides", "boolean", "Restore overrides to default values at program end."),
            "61" to listOf(
                "Ignore door when idle",
                "boolean",
                "Enable this if it is desirable to open the safety door when in IDLE mode (eg. for jogging)."
            ),
            "62" to listOf("Sleep enable", "boolean", "Enable sleep mode."),
            "63" to listOf("Disable laser", "boolean", "Disable laser during hold."),
            "64" to listOf("Force init alarm", "boolean", "Starts Grbl in alarm mode after a cold reset."),
            "65" to listOf(
                "Check limits at init",
                "boolean",
                "If limit switches are engaged after reset this forces Grbl to start in alarm mode."
            ),
            "100" to listOf("X-axis travel resolution", "step/mm", "X-axis travel resolution in steps per millimeter."),
            "101" to listOf("Y-axis travel resolution", "step/mm", "Y-axis travel resolution in steps per millimeter."),
            "102" to listOf("Z-axis travel resolution", "step/mm", "Z-axis travel resolution in steps per millimeter."),
            "110" to listOf("X-axis maximum rate", "mm/min", "X-axis maximum rate. Used as G0 rapid rate."),
            "111" to listOf("Y-axis maximum rate", "mm/min", "Y-axis maximum rate. Used as G0 rapid rate."),
            "112" to listOf("Z-axis maximum rate", "mm/min", "Z-axis maximum rate. Used as G0 rapid rate."),
            "120" to listOf(
                "X-axis acceleration",
                "mm/sec^2",
                "X-axis acceleration. Used for motion planning to not exceed motor torque and lose."
            ),
            "121" to listOf(
                "Y-axis acceleration",
                "mm/sec^2",
                "Y-axis acceleration. Used for motion planning to not exceed motor torque and lose."
            ),
            "122" to listOf(
                "Z-axis acceleration",
                "mm/sec^2",
                "Z-axis acceleration. Used for motion planning to not exceed motor torque and lose."
            ),
            "130" to listOf(
                "X-axis maximum travel",
                "mm",
                "Maximum X-axis travel distance from homing switch. Determines valid machine space for."
            ),
            "131" to listOf(
                "Y-axis maximum travel",
                "mm",
                "Maximum Y-axis travel distance from homing switch. Determines valid machine space for."
            ),
            "132" to listOf(
                "Z-axis maximum travel",
                "mm",
                "Maximum Z-axis travel distance from homing switch. Determines valid machine space for."
            ),
            "259" to listOf("Ortur Flame Sensor Debug", "boolean", "Flame Sensor Debug Mode (On/OFF)"),
            "260" to listOf("Ortur Flame Sensor Delta", "integer", "Flame Sensor Trigger Delta Value"),
            "261" to listOf("Ortur Flame Sensor Count", "integer", "Flame Sensor Trigger Count Treshold"),
            "262" to listOf("Ortur Gshock Sensor Threshold", "integer", "Gshock Sensor Treshold"),
            "263" to listOf("Ortur Auto Power Off", "minutes", "Auto Power Off in Minutes"),
            "\$-Code" to listOf("Setting", "Units", "Setting Description")
        ),
        // ortur.v1.7.x (78)
        "ortur.v1.7.x" to linkedMapOf<String, List<String>>(
            "0" to listOf("Step pulse time", "microseconds", "Sets time length per step. Minimum 3 microseconds."),
            "1" to listOf(
                "Step idle delay",
                "milliseconds",
                "Sets a short hold delay when stopping to let dynamics settle before disabling steppers. Value 255 keeps motors enabled."
            ),
            "2" to listOf("Step pulse invert", "mask", "Inverts the step signals (active low)."),
            "3" to listOf("Step direction invert", "mask", "Inverts the direction signals (active low)."),
            "4" to listOf(
                "Invert step enable pin",
                "boolean",
                "Inverts the stepper driver enable signals (active low). If the stepper drivers shares the same enable signal only X is used."
            ),
            "5" to listOf("Invert limit pins", "mask", "Inverts the axis limit input signals."),
            "6" to listOf("Invert probe pin", "boolean", "Inverts the probe input pin signal."),
            "10" to listOf("Status report options", "mask", "Specifies optional data included in status reports."),
            "11" to listOf(
                "Junction deviation",
                "mm",
                "Sets how fast Grbl travels through consecutive motions. Lower value slows it down."
            ),
            "12" to listOf(
                "Arc tolerance",
                "mm",
                "Sets the G2 and G3 arc tracing accuracy based on radial error. Beware: A very small value may effect performance."
            ),
            "13" to listOf(
                "Report in inches",
                "boolean",
                "Enables inch units when returning any position and rate value that is not a settings value."
            ),
            "14" to listOf("Invert control pins", "mask", "Inverts the control signals (active low)."),
            "15" to listOf("Invert coolant pins", "mask", "Inverts the coolant and mist signals (active low)."),
            "16" to listOf(
                "Invert spindle signals",
                "mask",
                "Inverts the spindle on counterclockwise and PWM signals (active low)."
            ),
            "17" to listOf(
                "Pullup disable control pins",
                "mask",
                "Disable the control signals pullup resistors. Potentially enables pulldown resistor if available."
            ),
            "18" to listOf(
                "Pullup disable limit pins",
                "mask",
                "Disable the limit signals pullup resistors. Potentially enables pulldown resistor if available."
            ),
            "19" to listOf(
                "Pullup disable probe pin",
                "boolean",
                "Disable the probe signal pullup resistor. Potentially enables pulldown resistor if available."
            ),
            "20" to listOf(
                "Soft limits enable",
                "boolean",
                "Enables soft limits checks within machine travel and sets alarm when exceeded. Requires homing."
            ),
            "21" to listOf(
                "Hard limits enable",
                "mask",
                "When enabled immediately halts motion and throws an alarm when switch is triggered. In strict mode only homing is possible after switch is triggered."
            ),
            "22" to listOf("Homing cycle enable", "boolean", "Enables homing cycle. Requires limit switches on all axes."),
            "23" to listOf(
                "Homing direction invert",
                "mask",
                "Homing searches for a switch in the positive direction. Set axis bit to search in negative direction."
            ),
            "24" to listOf(
                "Homing locate feed rate",
                "mm/min",
                "Feed rate to slowly engage limit switch to determine its location accurately."
            ),
            "25" to listOf(
                "Homing search seek rate",
                "mm/min",
                "Seek rate to quickly find the limit switch before the slower locating phase."
            ),
            "26" to listOf(
                "Homing switch debounce delay",
                "milliseconds",
                "Sets a short delay between phases of homing cycle to let a switch debounce."
            ),
            "27" to listOf(
                "Homing switch pull off distance",
                "mm",
                "Retract distance after triggering switch to disengage it. Homing will fail if switch isn't cleared."
            ),
            "28" to listOf("G73 Retract distance", "mm", "G73 retract distance (for chip breaking drilling)."),
            "29" to listOf("Pulse delay", "microseconds", "Step pulse delay."),
            "30" to listOf("Maximum spindle speed", "RPM", "Maximum spindle speed. Sets PWM to maximum duty cycle."),
            "31" to listOf("Minimum spindle speed", "RPM", "Minimum spindle speed. Sets PWM to minimum duty cycle."),
            "32" to listOf(
                "Mode of operation",
                "integer",
                "Laser mode: consecutive G1/2/3 commands will not halt when spindle speed is changed. Lathe mode: allows use of G7/G8/G96 and G97."
            ),
            "33" to listOf("PWM frequency", "Hz", "PWM frequency."),
            "34" to listOf("PWM off value", "percent", "PWM off value in percent (duty cycle)."),
            "35" to listOf("PWM min value", "percent", "PWM min value in percent (duty cycle)."),
            "36" to listOf("PWM max value", "percent", "PWM max value in percent (duty cycle)."),
            "37" to listOf("Steppers deenergize", "mask", "Specifies which steppers not to disable when stopped."),
            "39" to listOf(
                "Enable legacy RT commands",
                "boolean",
                "Enables \"normal\" processing of ? ! and ~ characters when part of \$setting or comment. If disabled then they are added to the input string instead."
            ),
            "40" to listOf("Limit jog commands", "boolean", "Limit jog commands to machine limits for homed axes."),
            "41" to listOf("Safety Door", "boolean", "Enable Safety Door."),
            "42" to listOf("Safety Door", "mask", "Define which axis that performs the parking motion."),
            "43" to listOf("Homing passes", "integer", "Number of homing passes. Minimum 1 maximum 128."),
            "44" to listOf("Axes homing", "mask", "Axes to home in first pass."),
            "45" to listOf("Axes homing", "mask", "Axes to home in second pass."),
            "46" to listOf("Axes homing", "mask", "Axes to home in third pass."),
            "56" to listOf("Safety Door", "boolean", "Spindle pull out and plunge distance in mm. Incremental distance."),
            "57" to listOf("Safety Door", "integer", "Pull out/plunge slow feed rate in mm/min."),
            "58" to listOf("Safety Door", "integer", "Parking axis target. In mm  as machine coordinate"),
            "59" to listOf("Safety Door", "integer", "Parking fast rate after pull out in mm/min."),
            "60" to listOf("Restore overrides", "boolean", "Restore overrides to default values at program end."),
            "61" to listOf(
                "Ignore door when idle",
                "boolean",
                "Enable this if it is desirable to open the safety door when in IDLE mode (eg. for jogging)."
            ),
            "62" to listOf("Sleep enable", "boolean", "Enable sleep mode."),
            "63" to listOf("Disable laser", "boolean", "Disable laser during hold."),
            "64" to listOf("Force init alarm", "boolean", "Starts Grbl in alarm mode after a cold reset."),
            "65" to listOf(
                "Check limits at init",
                "boolean",
                "If limit switches are engaged after reset this forces Grbl to start in alarm mode."
            ),
            "100" to listOf("X-axis travel resolution", "step/mm", "X-axis travel resolution in steps per millimeter."),
            "101" to listOf("Y-axis travel resolution", "step/mm", "Y-axis travel resolution in steps per millimeter."),
            "102" to listOf("Z-axis travel resolution", "step/mm", "Z-axis travel resolution in steps per millimeter."),
            "110" to listOf("X-axis maximum rate", "mm/min", "X-axis maximum rate. Used as G0 rapid rate."),
            "111" to listOf("Y-axis maximum rate", "mm/min", "Y-axis maximum rate. Used as G0 rapid rate."),
            "112" to listOf("Z-axis maximum rate", "mm/min", "Z-axis maximum rate. Used as G0 rapid rate."),
            "120" to listOf(
                "X-axis acceleration",
                "mm/sec^2",
                "X-axis acceleration. Used for motion planning to not exceed motor torque and lose."
            ),
            "121" to listOf(
                "Y-axis acceleration",
                "mm/sec^2",
                "Y-axis acceleration. Used for motion planning to not exceed motor torque and lose."
            ),
            "122" to listOf(
                "Z-axis acceleration",
                "mm/sec^2",
                "Z-axis acceleration. Used for motion planning to not exceed motor torque and lose."
            ),
            "130" to listOf(
                "X-axis maximum travel",
                "mm",
                "Maximum X-axis travel distance from homing switch. Determines valid machine space for."
            ),
            "131" to listOf(
                "Y-axis maximum travel",
                "mm",
                "Maximum Y-axis travel distance from homing switch. Determines valid machine space for."
            ),
            "132" to listOf(
                "Z-axis maximum travel",
                "mm",
                "Maximum Z-axis travel distance from homing switch. Determines valid machine space for."
            ),
            "259" to listOf("Ortur Flame Sensor Debug", "boolean", "Flame Sensor Debug Mode (On/OFF)"),
            "260" to listOf("Ortur Flame Sensor Delta", "integer", "Flame Sensor Trigger Delta Value"),
            "261" to listOf("Ortur Flame Sensor Count", "integer", "Flame Sensor Trigger Count Treshold"),
            "262" to listOf("Ortur Gshock Sensor Threshold", "integer", "Gshock Sensor Treshold"),
            "263" to listOf("Ortur Auto Power Off", "minutes", "Auto Power Off in Minutes"),
            "264" to listOf("DLC Total Duration", "seconds", "Digital Laser Control - Total Laser Duration in Seconds"),
            "265" to listOf("DLC Calibration Focus", "mm", "Digital Laser Control - Calibration Focus for Autofocus"),
            "266" to listOf("DLC Communication Rate", "", "Digital Laser Control - Communication Rate"),
            "267" to listOf(
                "DLC Mode",
                "boolean",
                "Digital Laser Control- Laser-driven mode (Default PWM mode, Digital mode)"
            ),
            "268" to listOf(
                "Console Echo Debug",
                "",
                "Set Echo Debug On Console (Default 0) [Incompatible with LightBurn Versions Under 1.0.0]"
            ),
            "269" to listOf(
                "Power Source Debug",
                "",
                "Set Debug Output for Output Voltage and Current. Allows diagnostics on input power source"
            ),
            "270" to listOf(
                "Offline controller Baud Rate",
                "integer",
                "Set the Baud Rate for offline screen communication x100 (default 5120)"
            ),
            "\$-Code" to listOf("Setting", "Units", "Setting Description")
        ),
        // ortur.GrblHal (115)
        "ortur.GrblHal" to linkedMapOf<String, List<String>>(
            "0" to listOf(
                "Step pulse time",
                "microseconds",
                "Sets time length per step. Minimum 2 microseconds.    This needs to be reduced from the default value of 10 when max. step rates exceed approximately 80 kHz."
            ),
            "1" to listOf(
                "Step idle delay",
                "milliseconds",
                "Sets a short hold delay when stopping to let dynamics settle before disabling steppers. Value 255 keeps motors enabled."
            ),
            "2" to listOf("Step pulse invert", "mask", "Inverts the step signals (active low)."),
            "3" to listOf("Step direction invert", "mask", "Inverts the direction signals (active low)."),
            "4" to listOf(
                "Invert stepper enable pin(s)",
                "boolean",
                "Inverts the stepper driver enable signals. Most drivers uses active low enable requiring inversion.    NOTE: If the stepper drivers shares the same enable signal only X is used."
            ),
            "5" to listOf("Invert limit pins", "boolean", "Inverts the axis limit input signals."),
            "7" to listOf("Disable spindle with zero speed", "boolean", ""),
            "10" to listOf(
                "Status report options",
                "mask",
                "Specifies optional data included in status reports.  If Run substatus is enabled it may be used for simple probe protection.    NOTE: Parser state will be sent separately after the status report and only on changes."
            ),
            "11" to listOf(
                "Junction deviation",
                "millimeters",
                "Sets how fast Grbl travels through consecutive motions. Lower value slows it down."
            ),
            "12" to listOf(
                "Arc tolerance",
                "millimeters",
                "Sets the G2 and G3 arc tracing accuracy based on radial error. Beware: A very small value may effect performance."
            ),
            "13" to listOf(
                "Report in inches",
                "boolean",
                "Enables inch units when returning any position and rate value that is not a settings value."
            ),
            "14" to listOf(
                "Invert control pins",
                "mask",
                "Inverts the control signals (active low).  NOTE: Block delete Optional stop EStop and Probe connected are optional signals availability is driver dependent."
            ),
            "15" to listOf("Invert coolant pins", "mask", "Inverts the coolant and mist signals (active low)."),
            "16" to listOf(
                "Invert spindle signals",
                "mask",
                "Inverts the spindle on counterclockwise and PWM signals (active low)."
            ),
            "17" to listOf(
                "Pullup disable control pins",
                "mask",
                "Disable the control signals pullup resistors. Potentially enables pulldown resistor if available.  NOTE: Block delete Optional stop and EStop are optional signals availability is driver dependent."
            ),
            "18" to listOf(
                "Pullup disable limit pins",
                "mask",
                "Disable the limit signals pullup resistors. Potentially enables pulldown resistor if available."
            ),
            "20" to listOf(
                "Soft limits enable",
                "boolean",
                "Enables soft limits checks within machine travel and sets alarm when exceeded. Requires homing."
            ),
            "21" to listOf(
                "Hard limits enable",
                "boolean",
                "When enabled immediately halts motion and throws an alarm when a limit switch is triggered. In strict mode only homing is possible when a switch is engaged."
            ),
            "22" to listOf(
                "Homing cycle",
                "boolean",
                "Enables homing cycle. Requires limit switches on axes to be automatically homed. When `Enable single axis commands` is checked single axis homing can be performed by \$H<axis letter> commands.    When `Allow manual` is checked axes not homed automatically ay be  manually by \$H or \$H<axis letter> commands.    `Override locks` is for allowing a soft reset to disable `Homing on startup required`.  NOTE: Block delete Optional stop and EStop are optional signals availability is driver dependent."
            ),
            "23" to listOf(
                "Homing direction invert",
                "mask",
                "Homing searches for a switch in the positive direction. Set axis bit to search in negative direction."
            ),
            "24" to listOf(
                "Homing locate feed rate",
                "mm/min",
                "Feed rate to slowly engage limit switch to determine its location accurately."
            ),
            "25" to listOf(
                "Homing search seek rate",
                "mm/min",
                "Seek rate to quickly find the limit switch before the slower locating phase."
            ),
            "26" to listOf(
                "Homing switch debounce delay",
                "milliseconds",
                "Sets a short delay between phases of homing cycle to let a switch debounce."
            ),
            "27" to listOf(
                "Homing switch pull-off distance",
                "mm",
                "Retract distance after triggering switch to disengage it. Homing will fail if switch isn't cleared."
            ),
            "28" to listOf("G73 Retract distance", "mm", "G73 retract distance (for chip breaking drilling)."),
            "29" to listOf(
                "Pulse delay",
                "microseconds",
                "Step pulse delay.    Normally leave this at 0 as there is an implicit delay on direction changes when AMASS is active."
            ),
            "30" to listOf("Maximum spindle speed", "RPM", "Maximum spindle speed. Sets PWM to maximum duty cycle."),
            "31" to listOf("Minimum spindle speed", "RPM", "Minimum spindle speed. Sets PWM to minimum duty cycle."),
            "32" to listOf(
                "Mode of operation",
                "integer",
                "Laser mode: consecutive G1/2/3 commands will not halt when spindle speed is changed.  Lathe mode: allows use of G7 G8 G96 and G97."
            ),
            "33" to listOf("Spindle PWM frequency", "Hz", "Spindle PWM frequency."),
            "34" to listOf("Spindle PWM off value", "percent", "Spindle PWM off value in percent (duty cycle)."),
            "35" to listOf("Spindle PWM min value", "percent", "Spindle PWM min value in percent (duty cycle)."),
            "36" to listOf("Spindle PWM max value", "percent", "Spindle PWM max value in percent (duty cycle)."),
            "37" to listOf("Steppers deenergize", "mask", "Specifies which steppers not to disable when stopped."),
            "39" to listOf(
                "Enable legacy RT commands",
                "boolean",
                "Enables normal processing of ? ! and ~ characters when part of \$-setting or comment. If disabled then they are added to the input string instead."
            ),
            "40" to listOf("Limit jog commands", "boolean", "Limit jog commands to machine limits for homed axes."),
            "43" to listOf("Homing passes", "integer", "Number of homing passes. Minimum 1 maximum 128."),
            "44" to listOf("Axes homing first pass", "mask", "Axes to home in first pass."),
            "45" to listOf("Axes homing second pass", "mask", "Axes to home in second pass."),
            "46" to listOf("Axes homing third pass", "mask", "Axes to home in third pass."),
            "62" to listOf("Sleep enable", "boolean", "Enable sleep mode."),
            "63" to listOf("Feed hold actions", "", "Actions taken during feed hold and on resume from feed hold."),
            "64" to listOf("Force init alarm", "boolean", "Starts Grbl in alarm mode after a cold reset."),
            "70" to listOf(
                "Network Services",
                "",
                "Network services to enable. Consult driver documentation for availability.    NOTE: A hard reset of the controller is required after changing this setting."
            ),
            "73" to listOf("WiFi Mode", "", "WiFi Mode."),
            "74" to listOf("WiFi Station (STA) SSID", "string", "WiFi Station (STA) SSID."),
            "75" to listOf("WiFi Station (STA) Password", "string", "WiFi Station (STA) Password."),
            "76" to listOf("WiFi Access Point (AP) SSID", "string", "WiFi Access Point (AP) SSID."),
            "77" to listOf("WiFi Access Point (AP) Password", "string", "WiFi Access Point (AP) Password."),
            "100" to listOf("X-axis travel resolution", "step/mm", "Travel resolution in steps per millimeter."),
            "101" to listOf("Y-axis travel resolution", "step/mm", "Travel resolution in steps per millimeter."),
            "102" to listOf("Z-axis travel resolution", "step/mm", "Travel resolution in steps per millimeter."),
            "110" to listOf("X-axis maximum rate", "mm/min", "Maximum rate. Used as G0 rapid rate."),
            "111" to listOf("Y-axis maximum rate", "mm/min", "Maximum rate. Used as G0 rapid rate."),
            "112" to listOf("Z-axis maximum rate", "mm/min", "Maximum rate. Used as G0 rapid rate."),
            "120" to listOf(
                "X-axis acceleration",
                "mm/sec^2",
                "Acceleration. Used for motion planning to not exceed motor torque and lose steps."
            ),
            "121" to listOf(
                "Y-axis acceleration",
                "mm/sec^2",
                "Acceleration. Used for motion planning to not exceed motor torque and lose steps."
            ),
            "122" to listOf(
                "Z-axis acceleration",
                "mm/sec^2",
                "Acceleration. Used for motion planning to not exceed motor torque and lose steps."
            ),
            "130" to listOf(
                "X-axis maximum travel",
                "mm",
                "Maximum axis travel distance from homing switch. Determines valid machine space for soft-limits and homing search distances."
            ),
            "131" to listOf(
                "Y-axis maximum travel",
                "mm",
                "Maximum axis travel distance from homing switch. Determines valid machine space for soft-limits and homing search distances."
            ),
            "132" to listOf(
                "Z-axis maximum travel",
                "mm",
                "Maximum axis travel distance from homing switch. Determines valid machine space for soft-limits and homing search distances."
            ),
            "140" to listOf("X-axis motor current", "mA", "Motor current in mA (RMS)."),
            "141" to listOf("Y-axis motor current", "mA", "Motor current in mA (RMS)."),
            "142" to listOf("Z-axis motor current", "mA", "Motor current in mA (RMS)."),
            "150" to listOf("X-axis microsteps", "steps", "Microsteps per fullstep."),
            "151" to listOf("Y-axis microsteps", "steps", "Microsteps per fullstep."),
            "152" to listOf("Z-axis microsteps", "steps", "Microsteps per fullstep."),
            "160" to listOf("X-axis backlash compensation", "mm", "Backlash distance to compensate for."),
            "161" to listOf("Y-axis backlash compensation", "mm", "Backlash distance to compensate for."),
            "162" to listOf("Z-axis backlash compensation", "mm", "Backlash distance to compensate for."),
            "200" to listOf("X-axis StallGuard4 fast threshold", "", "StallGuard threshold for fast (seek) homing phase."),
            "201" to listOf("Y-axis StallGuard4 fast threshold", "", "StallGuard threshold for fast (seek) homing phase."),
            "202" to listOf("Z-axis StallGuard4 fast threshold", "", "StallGuard threshold for fast (seek) homing phase."),
            "210" to listOf(
                "X-axis hold current",
                "%",
                "Motor current at standstill as a percentage of full current. NOTE: if grblHAL is configured to disable motors on standstill this setting has no use."
            ),
            "211" to listOf(
                "Y-axis hold current",
                "%",
                "Motor current at standstill as a percentage of full current. NOTE: if grblHAL is configured to disable motors on standstill this setting has no use."
            ),
            "212" to listOf(
                "Z-axis hold current",
                "%",
                "Motor current at standstill as a percentage of full current. NOTE: if grblHAL is configured to disable motors on standstill this setting has no use."
            ),
            "220" to listOf("X-axis StallGuard4 slow threshold", "", "StallGuard threshold for slow (feed) homing phase."),
            "221" to listOf("Y-axis StallGuard4 slow threshold", "", "StallGuard threshold for slow (feed) homing phase."),
            "222" to listOf("Z-axis StallGuard4 slow threshold", "", "StallGuard threshold for slow (feed) homing phase."),
            "300" to listOf(
                "Hostname",
                "string",
                "Network hostname.    NOTE: A hard reset of the controller is required after changing this setting."
            ),
            "302" to listOf(
                "IP Address",
                "IPv4 address",
                "Static IP address.    NOTE: A hard reset of the controller is required after changing this setting."
            ),
            "303" to listOf(
                "Gateway",
                "IPv4 address",
                "Static gateway address.    NOTE: A hard reset of the controller is required after changing this setting."
            ),
            "304" to listOf(
                "Netmask",
                "IPv4 mask",
                "Static netmask.    NOTE: A hard reset of the controller is required after changing this setting."
            ),
            "305" to listOf(
                "Telnet port",
                "integer",
                "(Raw) Telnet port number listening for incoming connections.    NOTE: A hard reset of the controller is required after changing this setting."
            ),
            "306" to listOf(
                "HTTP port",
                "integer",
                "HTTP port number listening for incoming connections.    NOTE: A hard reset of the controller is required after changing this setting."
            ),
            "307" to listOf(
                "Websocket port",
                "integer",
                "Websocket port number listening for incoming connections.    NOTE: A hard reset of the controller is required after changing this setting.NOTE: WebUI requires this to be HTTP port number + 1."
            ),
            "308" to listOf(
                "FTP port",
                "integer",
                "FTP port number listening for incoming connections.    NOTE: A hard reset of the controller is required after changing this setting."
            ),
            "310" to listOf(
                "Hostname (AP)",
                "string",
                "Network hostname.    NOTE: A hard reset of the controller is required after changing this setting."
            ),
            "312" to listOf(
                "IP Address (AP)",
                "IPv4 address",
                "Static IP address.    NOTE: A hard reset of the controller is required after changing this setting."
            ),
            "313" to listOf(
                "Gateway (AP)",
                "IPv4 address",
                "Static gateway address.    NOTE: A hard reset of the controller is required after changing this setting."
            ),
            "314" to listOf(
                "Netmask (AP)",
                "IPv4 mask",
                "Static netmask.    NOTE: A hard reset of the controller is required after changing this setting."
            ),
            "330" to listOf("Admin Password", "string", "Administrator password."),
            "331" to listOf("User Password", "string", "User password."),
            "339" to listOf(
                "Sensorless homing",
                "boolean",
                "Enable sensorless homing for axis. Requires SPI controlled Trinamic drivers."
            ),
            "341" to listOf(
                "Tool change mode",
                "",
                "Normal: allows jogging for manual touch off. Set new position manually.   Manual touch off: retracts tool axis to home position for tool change use jogging or \$TPW for touch off.   Manual touch off  G59.3: retracts tool axis to home position then to G59.3 position for tool change use jogging or \$TPW for touch off.    Automatic touch off  G59.3: retracts tool axis to home position for tool change then to G59.3 position for automatic touch off.   All modes except Normal and Ignore M6 returns the tool (controlled point) to original position after touch off."
            ),
            "342" to listOf(
                "Tool change probing distance",
                "mm",
                "Maximum probing distance for automatic or \$TPW touch off."
            ),
            "343" to listOf(
                "Tool change locate feed rate",
                "mm/min",
                "Feed rate to slowly engage tool change sensor to determine the tool offset accurately."
            ),
            "344" to listOf(
                "Tool change search seek rate",
                "mm/min",
                "Seek rate to quickly find the tool change sensor before the slower locating phase."
            ),
            "345" to listOf(
                "Tool change probe pull-off rate",
                "mm/min",
                "Pull-off rate for the retract move before the slower locating phase."
            ),
            "384" to listOf(
                "Disable G92 persistence",
                "",
                "Disables save/restore of G92 offset to non-volatile storage (NVS)."
            ),
            "600" to listOf(
                "Power log enable",
                "boolean",
                "Whether to enable the main power supply power supply debugging information."
            ),
            "601" to listOf("Voltage offset", "v", "Maximum voltage offset allowed for the main power supply."),
            "602" to listOf("device auto poweroff time", "s", "Maximum voltage offset allowed for the main power supply."),
            "603" to listOf(
                "Shock detection alarm threshold",
                "",
                "Value range: 0~1000 The Recommended value = 3 If the alarm threshold for shock detection is set to 0 the function is disabled."
            ),
            "604" to listOf(
                "Skew detection alarm threshold",
                "",
                "Value range: 0~1000 The Recommended value = 4 If the alarm threshold for skew detection is set to 0 the function is disabled."
            ),
            "605" to listOf("Buzz enable", "boolean", "Enable buzz function"),
            "606" to listOf("Homing upon power up enable", "boolean", "Homing upon power up"),
            "607" to listOf("Report echo line received enable", "boolean", "Report echo line received enable."),
            "608" to listOf("Return Line number enable", "boolean", "Return Line number enable"),
            "609" to listOf("Homing switch seek pull-off distance", "mm", "Homing seek pull-off distance"),
            "610" to listOf("Language", "", "Set the firmware language"),
            "611" to listOf("Auto power on forever", "boolean", "Enable auto-poweron function"),
            "612" to listOf("Webui auth enable", "boolean", "Enable webui auth function"),
            "622" to listOf(
                "Homing cycle",
                "boolean",
                "Enables homing cycle. Requires limit switches on axes to be automatically homed. When `Enable single axis commands` is checked single axis homing can be performed by \$H<axis letter> commands.When `Allow manual` is checked axes not homed automatically may be homed manually by \$H or \$H<axis letter> commands.`Override locks` is for allowing a soft reset to disable `Homing on startup required`."
            ),
            "\$-Code" to listOf("Setting", "Units", "Setting Description")
        ),
        // longer.nanoduo (35)
        "longer.nanoduo" to linkedMapOf<String, List<String>>(
            "0" to listOf("Step pulse time", "microseconds", "Sets time length per step. Minimum 1 usec."),
            "1" to listOf(
                "Step idle delay",
                "milliseconds",
                "Sets a short hold delay when stopping to let dynamics settle before disabling steppers. Value 255 keeps motors enabled with no delay."
            ),
            "2" to listOf("Step pulse invert", "mask", "Inverts the step signal. Set axis bit to invert (000000YX)."),
            "3" to listOf(
                "Step direction invert",
                "mask",
                "Inverts the direction signal. Set axis bit to invert (000000YX)."
            ),
            "4" to listOf("Invert step enable pin", "boolean", "Inverts the stepper driver enable pin signal."),
            "5" to listOf("Invert limit pins", "boolean", "Inverts all of the limit input pins."),
            "6" to listOf("Invert probe pin", "boolean", "Inverts the probe input pin signal."),
            "10" to listOf("Status report options", "mask", "Alters data included in status reports."),
            "11" to listOf(
                "Junction deviation",
                "millimeters",
                "Sets how fast Grbl travels through consecutive motions. Lower value slows it down."
            ),
            "12" to listOf(
                "Arc tolerance",
                "millimeters",
                "Sets the G2 and G3 arc tracing accuracy based on radial error. Beware: A very small value may affect performance."
            ),
            "13" to listOf(
                "Report in inches",
                "boolean",
                "Enables inch units when returning any position and rate value that is not a settings value."
            ),
            "20" to listOf(
                "Soft limits enable",
                "boolean",
                "Enables soft limits checks within machine travel and sets alarm when exceeded. Requires homing."
            ),
            "21" to listOf(
                "Hard limits enable",
                "boolean",
                "Enables hard limits. Immediately halts motion and throws an alarm when switch is triggered."
            ),
            "22" to listOf("Homing cycle enable", "boolean", "Enables homing cycle. Requires limit switches on all axes."),
            "23" to listOf(
                "Homing direction invert",
                "mask",
                "Homing searches for a switch in the positive direction. Set axis bit (000000YX) to search in negative direction."
            ),
            "24" to listOf(
                "Homing locate feed rate",
                "mm/min",
                "Feed rate to slowly engage limit switch to determine its location accurately."
            ),
            "25" to listOf(
                "Homing search seek rate",
                "mm/min",
                "Seek rate to quickly find the limit switch before the slower locating phase."
            ),
            "26" to listOf(
                "Homing switch debounce delay",
                "milliseconds",
                "Sets a short delay between phases of homing cycle to let a switch debounce."
            ),
            "27" to listOf(
                "Homing switch pull-off distance",
                "millimeters",
                "Retract distance after triggering switch to disengage it. Homing will fail if switch isn't cleared."
            ),
            "30" to listOf("Maximum spindle speed", "RPM", "Maximum spindle speed. Sets PWM to 100% duty cycle."),
            "31" to listOf("Minimum spindle speed", "RPM", "Minimum spindle speed. Sets PWM to 0.4% or lowest duty cycle."),
            "32" to listOf(
                "Laser-mode enable",
                "boolean",
                "Enables laser mode. Consecutive G1/2/3 commands will not halt when spindle speed is changed."
            ),
            "100" to listOf("X-axis travel resolution", "step/mm", "X-axis travel resolution in steps per millimeter."),
            "101" to listOf("Y-axis travel resolution", "step/mm", "Y-axis travel resolution in steps per millimeter."),
            "102" to listOf("Z-axis travel resolution", "step/mm", "Z-axis travel resolution in steps per millimeter."),
            "110" to listOf("X-axis maximum rate", "mm/min", "X-axis maximum rate. Used as G0 rapid rate."),
            "111" to listOf("Y-axis maximum rate", "mm/min", "Y-axis maximum rate. Used as G0 rapid rate."),
            "112" to listOf("Z-axis maximum rate", "mm/min", "Z-axis maximum rate. Used as G0 rapid rate."),
            "120" to listOf(
                "X-axis acceleration",
                "mm/sec^2",
                "X-axis acceleration. Used for motion planning to not exceed motor torque and lose steps."
            ),
            "121" to listOf(
                "Y-axis acceleration",
                "mm/sec^2",
                "Y-axis acceleration. Used for motion planning to not exceed motor torque and lose steps."
            ),
            "122" to listOf(
                "Z-axis acceleration",
                "mm/sec^2",
                "Z-axis acceleration. Used for motion planning to not exceed motor torque and lose steps."
            ),
            "130" to listOf(
                "X-axis maximum travel",
                "millimeters",
                "Maximum X-axis travel distance from homing switch. Determines valid machine space for soft-limits and homing search distances."
            ),
            "131" to listOf(
                "Y-axis maximum travel",
                "millimeters",
                "Maximum Y-axis travel distance from homing switch. Determines valid machine space for soft-limits and homing search distances."
            ),
            "132" to listOf(
                "Z-axis maximum travel",
                "millimeters",
                "Maximum Z-axis travel distance from homing switch. Determines valid machine space for soft-limits and homing search distances."
            ),
            "\$-Code" to listOf("Setting", "Units", "Setting Description")
        )
    )

    // ALARM_CODES: 3 个固件族, 39 条记录
    val ALARM_CODES: Map<String, Map<String, List<String>>> = linkedMapOf<String, Map<String, List<String>>>(
        // standard (9)
        "standard" to linkedMapOf<String, List<String>>(
            "1" to listOf(
                "Hard limit",
                "Hard limit has been triggered. Machine position is likely lost due to sudden halt. Re-homing is highly recommended."
            ),
            "2" to listOf(
                "Soft limit",
                "Soft limit alarm. G-code motion target exceeds machine travel. Machine position retained. Alarm may be safely unlocked."
            ),
            "3" to listOf(
                "Abort during cycle",
                "Reset while in motion. Machine position is likely lost due to sudden halt. Re-homing is highly recommended."
            ),
            "4" to listOf(
                "Probe fail",
                "Probe fail. Probe is not in the expected initial state before starting probe cycle when G38.2 and G38.3 is not triggered and G38.4 and G38.5 is triggered."
            ),
            "5" to listOf(
                "Probe fail",
                "Probe fail. Probe did not contact the workpiece within the programmed travel for G38.2 and G38.4."
            ),
            "6" to listOf("Homing fail", "Homing fail. The active homing cycle was reset."),
            "7" to listOf("Homing fail", "Homing fail. Safety door was opened during homing cycle."),
            "8" to listOf(
                "Homing fail",
                "Homing fail. Pull off travel failed to clear limit switch. Try increasing pull-off setting or check wiring."
            ),
            "9" to listOf(
                "Homing fail",
                "Homing fail. Could not find limit switch within search distances. Try increasing max travel or decreasing pull-off distance or check wiring."
            )
        ),
        // ortur.GrblHal (17)
        "ortur.GrblHal" to linkedMapOf<String, List<String>>(
            "1" to listOf(
                "Hard limit",
                "Hard limit has been triggered. Machine position is likely lost due to sudden halt. Re-homing is highly recommended."
            ),
            "2" to listOf(
                "Soft limit",
                "Soft limit alarm. G-code motion target exceeds machine travel. Machine position retained. Alarm may be safely unlocked."
            ),
            "3" to listOf(
                "Abort during cycle",
                "Reset while in motion. Machine position is likely lost due to sudden halt. Re-homing is highly recommended."
            ),
            "4" to listOf(
                "Probe fail",
                "Probe fail. Probe is not in the expected initial state before starting probe cycle when G38.2 and G38.3 is not triggered and G38.4 and G38.5 is triggered."
            ),
            "5" to listOf(
                "Probe fail",
                "Probe fail. Probe did not contact the workpiece within the programmed travel for G38.2 and G38.4."
            ),
            "6" to listOf("Homing fail", "Homing fail. The active homing cycle was reset."),
            "7" to listOf("Homing fail", "Homing fail. Safety door was opened during homing cycle."),
            "8" to listOf(
                "Homing fail",
                "Homing fail. Pull off travel failed to clear limit switch. Try increasing pull-off setting or check wiring."
            ),
            "9" to listOf(
                "Homing fail",
                "Homing fail. Could not find limit switch within search distances. Try increasing max travel or decreasing pull-off distance or check wiring."
            ),
            "10" to listOf("EStop fail", "EStop asserted. Clear and reset"),
            "11" to listOf("Homing required", "Homing required. Execute homing command (\$H) to continue."),
            "12" to listOf("Hard limit", "Limit switch engaged. Clear before continuing."),
            "13" to listOf("Probe fail", "Probe protection triggered. Clear before continuing."),
            "14" to listOf("Spindle timeout", "Spindle at speed timeout. Clear before continuing."),
            "15" to listOf(
                "Homing fail",
                "Homing fail. Could not find second limit switch for auto squared axis within search distances. Try increasing max travel decreasing pull-off distance or check wiring."
            ),
            "16" to listOf("POS fail", "Power on selftest (POS) failed."),
            "17" to listOf("Motor fault", "Motor fault.")
        ),
        // longer.nanoduo (13)
        "longer.nanoduo" to linkedMapOf<String, List<String>>(
            "1" to listOf(
                "Hard limit",
                "Hard limit has been triggered. Machine position is likely lost due to sudden halt. Re-homing is highly recommended."
            ),
            "2" to listOf(
                "Soft limit",
                "Soft limit alarm. G-code motion target exceeds machine travel. Machine position retained. Alarm may be safely unlocked."
            ),
            "3" to listOf(
                "Abort during cycle",
                "Reset while in motion. Machine position is likely lost due to sudden halt. Re-homing is highly recommended."
            ),
            "4" to listOf(
                "Probe fail",
                "Probe fail. Probe is not in the expected initial state before starting probe cycle when G38.2 and G38.3 is not triggered and G38.4 and G38.5 is triggered."
            ),
            "5" to listOf(
                "Probe fail",
                "Probe fail. Probe did not contact the workpiece within the programmed travel for G38.2 and G38.4."
            ),
            "6" to listOf("Homing fail", "Homing fail. The active homing cycle was reset."),
            "7" to listOf("Homing fail", "Homing fail. Safety door was opened during homing cycle."),
            "8" to listOf(
                "Homing fail",
                "Homing fail. Pull off travel failed to clear limit switch. Try increasing pull-off setting or check wiring."
            ),
            "9" to listOf(
                "Homing fail",
                "Homing fail. Could not find limit switch within search distances. Try increasing max travel or decreasing pull-off distance or check wiring."
            ),
            "10" to listOf("SpindleControl", "Spindle Control"),
            "11" to listOf("MontionSensor", "Warning!! MOTION Sensor Triggered"),
            "12" to listOf("FlameSensor", "Warning!! FLAME Sensor Triggered"),
            "13" to listOf("TemperatureSensor", "Warning!! TEMPERATURE Sensor Triggered")
        )
    )

    // ERROR_CODES: 3 个固件族, 158 条记录
    val ERROR_CODES: Map<String, Map<String, List<String>>> = linkedMapOf<String, Map<String, List<String>>>(
        // standard (35)
        "standard" to linkedMapOf<String, List<String>>(
            "1" to listOf("Expected command letter", "G-code words consist of a letter and a value. Letter was not found."),
            "2" to listOf(
                "Bad number format",
                "Missing the expected G-code word value or numeric value format is not valid."
            ),
            "3" to listOf("Invalid statement", "Grbl '\$' system command was not recognized or supported."),
            "4" to listOf("Value < 0", "Negative value received for an expected positive value."),
            "5" to listOf("Setting disabled", "Homing cycle failure. Homing is not enabled via settings."),
            "6" to listOf("Value < 3 usec", "Minimum step pulse time must be greater than 3usec."),
            "7" to listOf(
                "EEPROM read fail. Using defaults",
                "An EEPROM read failed. Auto-restoring affected EEPROM to default values."
            ),
            "8" to listOf(
                "Not idle",
                "Grbl '\$' command cannot be used unless Grbl is IDLE. Ensures smooth operation during a job."
            ),
            "9" to listOf("G-code lock", "G-code commands are locked out during alarm or jog state."),
            "10" to listOf("Homing not enabled", "Soft limits cannot be enabled without homing also enabled."),
            "11" to listOf("Line overflow", "Max characters per line exceeded. Received command line was not executed."),
            "12" to listOf(
                "Step rate > 30kHz",
                "Grbl '\$' setting value cause the step rate to exceed the maximum supported."
            ),
            "13" to listOf("Check Door", "Safety door detected as opened and door state initiated."),
            "14" to listOf(
                "Line length exceeded",
                "Build info or startup line exceeded EEPROM line length limit. Line not stored."
            ),
            "15" to listOf("Travel exceeded", "Jog target exceeds machine travel. Jog command has been ignored."),
            "16" to listOf("Invalid jog command", "Jog command has no '=' or contains prohibited g-code."),
            "18" to listOf("Emergency stop engaged", "Please rotate emergency stop button to release."),
            "20" to listOf("Unsupported command", "Unsupported or invalid g-code command found in block."),
            "21" to listOf("Modal group violation", "More than one g-code command from same modal group found in block."),
            "22" to listOf("Undefined feed rate", "Feed rate has not yet been set or is undefined."),
            "23" to listOf("Invalid gcode ID:23", "G-code command in block requires an integer value."),
            "24" to listOf("Invalid gcode ID:24", "More than one g-code command that requires axis words found in block."),
            "25" to listOf("Invalid gcode ID:25", "Repeated g-code word found in block."),
            "26" to listOf(
                "Invalid gcode ID:26",
                "No axis words found in block for g-code command or current modal state which requires them."
            ),
            "27" to listOf("Invalid gcode ID:27", "Line number value is invalid."),
            "28" to listOf("Invalid gcode ID:28", "G-code command is missing a required value word."),
            "29" to listOf("Invalid gcode ID:29", "G59.x work coordinate systems are not supported."),
            "30" to listOf("Invalid gcode ID:30", "G53 only allowed with G0 and G1 motion modes."),
            "31" to listOf(
                "Invalid gcode ID:31",
                "Axis words found in block when no command or current modal state uses them."
            ),
            "32" to listOf("Invalid gcode ID:32", "G2 and G3 arcs require at least one in-plane axis word."),
            "33" to listOf("Invalid gcode ID:33", "Motion command target is invalid."),
            "34" to listOf("Invalid gcode ID:34", "Arc radius value is invalid."),
            "35" to listOf("Invalid gcode ID:35", "G2 and G3 arcs require at least one in-plane offset word."),
            "36" to listOf("Invalid gcode ID:36", "Unused value words found in block."),
            "37" to listOf(
                "Invalid gcode ID:37",
                "G43.1 dynamic tool length offset is not assigned to configured tool length axis."
            )
        ),
        // ortur.GrblHal (61)
        "ortur.GrblHal" to linkedMapOf<String, List<String>>(
            "1" to listOf("Expected command letter", "G-code words consist of a letter and a value. Letter was not found."),
            "2" to listOf(
                "Bad number format",
                "Missing the expected G-code word value or numeric value format is not valid."
            ),
            "3" to listOf("Invalid statement", "\$' system command was not recognized or supported."),
            "4" to listOf("Value < 0", "Negative value received for an expected positive value."),
            "5" to listOf("Setting disabled", "Homing cycle failure. Homing is not configured via settings."),
            "6" to listOf("Value < 3 usec", "Step pulse time must be greater or equal to 2 microseconds."),
            "7" to listOf(
                "EEPROM read fail. Using defaults",
                "A settings read failed. Auto-restoring affected settings to default values."
            ),
            "8" to listOf(
                "Not idle",
                "\$' command cannot be used unless controller state is IDLE. Ensures smooth operation during a job."
            ),
            "9" to listOf("G-code lock", "G-code commands are locked out during alarm or jog state."),
            "10" to listOf("Homing not enabled", "Soft limits cannot be enabled without homing also enabled."),
            "11" to listOf("Line overflow", "Max characters per line exceeded. Received command line was not executed."),
            "12" to listOf("Step rate > 30kHz", "\$' setting value cause the step rate to exceed the maximum supported."),
            "13" to listOf("Check Door", "Safety door detected as opened and door state initiated."),
            "14" to listOf(
                "Line length exceeded",
                "Build info or startup line exceeded line length limit. Line not stored."
            ),
            "15" to listOf("Travel exceeded", "Jog target exceeds machine travel. Jog command has been ignored."),
            "16" to listOf("Invalid jog command", "Jog command has no '=' or contains prohibited g-code."),
            "17" to listOf("PWM is needed", "Laser mode requires PWM output."),
            "18" to listOf("Reset asserted", "Reset asserted"),
            "19" to listOf("Non positive value", "Non positive value"),
            "20" to listOf("Unsupported command", "Unsupported or invalid g-code command found in block."),
            "21" to listOf("Modal group violation", "More than one g-code command from same modal group found in block."),
            "22" to listOf("Undefined feed rate", "Feed rate has not yet been set or is undefined."),
            "23" to listOf("Invalid gcode ID:23", "G-code command in block requires an integer value."),
            "24" to listOf("Invalid gcode ID:24", "More than one g-code command that requires axis words found in block."),
            "25" to listOf("Invalid gcode ID:25", "Repeated g-code word found in block."),
            "26" to listOf(
                "Invalid gcode ID:26",
                "No axis words found in block for g-code command or current modal state which requires them."
            ),
            "27" to listOf("Invalid gcode ID:27", "Line number value is invalid."),
            "28" to listOf("Invalid gcode ID:28", "G-code command is missing a required value word."),
            "29" to listOf("Invalid gcode ID:29", "G59.x work coordinate systems are not supported."),
            "30" to listOf("Invalid gcode ID:30", "G53 only allowed with G0 and G1 motion modes."),
            "31" to listOf(
                "Invalid gcode ID:31",
                "Axis words found in block when no command or current modal state uses them."
            ),
            "32" to listOf("Invalid gcode ID:32", "G2 and G3 arcs require at least one in-plane axis word."),
            "33" to listOf("Invalid gcode ID:33", "Motion command target is invalid."),
            "34" to listOf("Invalid gcode ID:34", "Arc radius value is invalid."),
            "35" to listOf("Invalid gcode ID:35", "G2 and G3 arcs require at least one in-plane offset word."),
            "36" to listOf("Invalid gcode ID:36", "Unused value words found in block."),
            "37" to listOf(
                "Invalid gcode ID:37",
                "G43.1 dynamic tool length offset is not assigned to configured tool length axis."
            ),
            "38" to listOf(
                "Invalid gcode ID:38",
                "Tool number greater than max supported value or undefined tool selected."
            ),
            "39" to listOf("Invalid gcode ID:39", "Value out of range."),
            "40" to listOf("Invalid gcode ID:40", "G-code command not allowed when tool change is pending."),
            "41" to listOf("Invalid gcode ID:41", "Spindle not running when motion commanded in CSS or spindle sync mode."),
            "42" to listOf("Invalid gcode ID:42", "Plane must be ZX for threading."),
            "43" to listOf("Invalid gcode ID:43", "Max. feed rate exceeded."),
            "44" to listOf("Invalid gcode ID:44", "RPM out of range."),
            "45" to listOf("Invalid gcode ID:45", "Only homing is allowed when a limit switch is engaged."),
            "46" to listOf("Invalid gcode ID:46", "Home machine to continue."),
            "47" to listOf("Invalid gcode ID:47", "ATC: current tool is not set. Set current tool with M61."),
            "48" to listOf("Invalid gcode ID:48", "Value word conflict."),
            "49" to listOf("POST Failed", "Power on self test failed. A hard reset is required."),
            "50" to listOf("Emergency stop", "Emergency stop active."),
            "51" to listOf("Motor fault", "Motor fault."),
            "52" to listOf("Value out of range", "Setting value is out of range."),
            "53" to listOf("Invalid gcode ID:53", "Setting is not available  possibly due to limited driver support."),
            "54" to listOf("Invalid gcode ID:54", "Retract position is less than drill depth."),
            "60" to listOf("SD Error 60", "SD Card mount failed."),
            "61" to listOf("SD Error 61", "SD Card file open/read failed."),
            "62" to listOf("SD Error 62", "SD Card directory listing failed."),
            "63" to listOf("SD Error 63", "SD Card directory not found."),
            "64" to listOf("SD Error 64", "SD Card file empty."),
            "65" to listOf("Function error", "function is error."),
            "90" to listOf("WiFi error", "WiFi error.")
        ),
        // longer.nanoduo (62)
        "longer.nanoduo" to linkedMapOf<String, List<String>>(
            "1" to listOf("Expected GCodecommand letter", "Expected GCodecommand letter"),
            "2" to listOf("Bad GCode number format", "Bad GCode number format"),
            "3" to listOf("Invalid \$ statement", "Invalid \$ statement"),
            "4" to listOf("Negative value", "Negative value"),
            "5" to listOf("Setting disabled", "Setting disabled"),
            "6" to listOf("Step pulse too short", "Step pulse too short"),
            "7" to listOf("Failed to read settings", "Failed to read settings"),
            "8" to listOf("Command requires idle state", "Command requires idle state"),
            "9" to listOf(
                "GCode cannot be executed in lock or alarm state",
                "GCode cannot be executed in lock or alarm state"
            ),
            "10" to listOf("Soft limit error", "Soft limit error"),
            "11" to listOf("Line too long", "Line too long"),
            "12" to listOf("Max step rate exceeded", "Max step rate exceeded"),
            "13" to listOf("Check door", "Check door"),
            "14" to listOf("Startup line too long", "Startup line too long"),
            "15" to listOf("Max travel exceeded during jog", "Max travel exceeded during jog"),
            "16" to listOf("Invalid jog command", "Invalid jog command"),
            "17" to listOf("Laser mode requires PWM output", "Laser mode requires PWM output"),
            "18" to listOf("No Homing/Cycle defined in settings", "No Homing/Cycle defined in settings"),
            "19" to listOf("Unsupported GCode command", "Unsupported GCode command"),
            "20" to listOf("Gcode modal group violation", "Gcode modal group violation"),
            "21" to listOf("Gcode undefined feed rate", "Gcode undefined feed rate"),
            "22" to listOf("Gcode command value not integer", "Gcode command value not integer"),
            "23" to listOf("Gcode axis command conflict", "Gcode axis command conflict"),
            "24" to listOf("Gcode word repeated", "Gcode word repeated"),
            "25" to listOf("Gcode no axis words", "Gcode no axis words"),
            "26" to listOf("Gcode invalid line number", "Gcode invalid line number"),
            "27" to listOf("Gcode value word missing", "Gcode value word missing"),
            "28" to listOf("Gcode unsupported coordinate system", "Gcode unsupported coordinate system"),
            "29" to listOf("Gcode G53 invalid motion mode", "Gcode G53 invalid motion mode"),
            "30" to listOf("Gcode extra axis words", "Gcode extra axis words"),
            "31" to listOf("Gcode no axis words in plane", "Gcode no axis words in plane"),
            "32" to listOf("Gcode invalid target", "Gcode invalid target"),
            "33" to listOf("Gcode arc radius error", "Gcode arc radius error"),
            "34" to listOf("Gcode no offsets in plane", "Gcode no offsets in plane"),
            "35" to listOf("Gcode unused words", "Gcode unused words"),
            "36" to listOf("Gcode G43 dynamic axis error", "Gcode G43 dynamic axis error"),
            "37" to listOf("Gcode max value exceeded", "Gcode max value exceeded"),
            "38" to listOf("P param max exceeded", "P param max exceeded"),
            "39" to listOf("Failed to mount device", "Failed to mount device"),
            "40" to listOf("Failed to read", "Failed to read"),
            "41" to listOf("Failed to open directory", "Failed to open directory"),
            "42" to listOf("Directory not found", "Directory not found"),
            "43" to listOf("File empty", "File empty"),
            "44" to listOf("File not found", "File not found"),
            "45" to listOf("Failed to open file", "Failed to open file"),
            "46" to listOf("Device is busy", "Device is busy"),
            "47" to listOf("Failed to delete directory", "Failed to delete directory"),
            "48" to listOf("Failed to delete file", "Failed to delete file"),
            "49" to listOf("Bluetooth failed to start", "Bluetooth failed to start"),
            "50" to listOf("WiFi failed to start", "WiFi failed to start"),
            "51" to listOf("Number out of range for setting", "Number out of range for setting"),
            "52" to listOf("Invalid value for setting", "Invalid value for setting"),
            "53" to listOf("Failed to send message", "Failed to send message"),
            "54" to listOf("Failed to store setting", "Failed to store setting"),
            "55" to listOf("Failed to get setting status", "Failed to get setting status"),
            "56" to listOf("Authentication failed!", "Authentication failed!"),
            "57" to listOf("Another interface is busy", "Another interface is busy"),
            "58" to listOf("Jog Cancelled", "Jog Cancelled"),
            "59" to listOf("Zip file failed to open", "Zip file failed to open"),
            "60" to listOf("Failed to get information from zip file", "Failed to get information from zip file"),
            "61" to listOf("The upgraded firmware was not found", "The upgraded firmware was not found"),
            "62" to listOf("Upgrade failed", "Upgrade failed")
        )
    )

    /** 便于快速自检: 三张表的条目数汇总（固件族下的记录条数之和）。 */
    val TOTAL_ENTRIES: Int =
        SETTING_CODES.values.sumOf { it.size } +
        ALARM_CODES.values.sumOf { it.size } +
        ERROR_CODES.values.sumOf { it.size }
}
