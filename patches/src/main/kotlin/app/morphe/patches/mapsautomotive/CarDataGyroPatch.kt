package app.morphe.patches.mapsautomotive

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.w3c.dom.Element
import java.util.logging.Logger

// Maps' dead reckoning on DiLink: the head unit's plain gyro (type 4) for Maps' inertial input, which
// only takes an uncalibrated gyro (type 16) or the automotive yaw-only gyro and otherwise registers no
// inertial sensor at all (nzp: "sensor2 = ohz.c; if null use ohz.d; if both null: nothing registered").
// The inertial input class is found by its yaw-only gyro string plus a getDefaultSensor(16) in its
// constructor. The patch
// - routes that getDefaultSensor(16) through CarData.defaultGyro (type 4 when 16 is missing, switch on);
// - passes every Sensor.getType() in the class through CarData.gyroType (4 reads as 16), so its events
//   and flushes take the type-16 path;
// - adds CarData's shell-only receiver (android.permission.DUMP) for the switches.
// The switch is on by default (CarData.KEY_GYRO). Verified in Maps 26.10.120003.E: ohz.

private const val CAR_DATA = "Lapp/morphe/extension/mapsautomotive/patches/CarData;"
private const val CAR_DATA_RECEIVER = "app.morphe.extension.mapsautomotive.patches.CarData\$ShellReceiver"
private const val CAR_DATA_ACTION = "app.morphe.mapsautomotive.CAR_DATA"
private const val LIMITED_AXES_GYRO = "android.sensor.gyroscope_limited_axes_uncalibrated"
private const val GET_DEFAULT_SENSOR = "Landroid/hardware/SensorManager;->getDefaultSensor(I)Landroid/hardware/Sensor;"
private const val GET_TYPE = "Landroid/hardware/Sensor;->getType()I"
private val gyroLogger = Logger.getLogger("app.morphe.patches.mapsautomotive.CarDataGyroPatch")

internal val carDataManifestPatch = resourcePatch {
    dependsOn(manifestPatch)
    execute {
        document("AndroidManifest.xml").use { document ->
            val application = document.getElementsByTagName("application").item(0) as Element
            val receivers = document.getElementsByTagName("receiver")
            for (i in 0 until receivers.length) {
                if ((receivers.item(i) as Element).getAttribute("android:name") == CAR_DATA_RECEIVER) return@use
            }
            application.appendChild(
                document.createElement("receiver").apply {
                    setAttribute("android:name", CAR_DATA_RECEIVER)
                    setAttribute("android:exported", "true")
                    setAttribute("android:permission", "android.permission.DUMP")
                    appendChild(
                        document.createElement("intent-filter").apply {
                            appendChild(document.createElement("action").apply { setAttribute("android:name", CAR_DATA_ACTION) })
                        },
                    )
                },
            )
        }
    }
}

@Suppress("unused")
val carDataGyroPatch = bytecodePatch(
    name = "Car data: head unit gyro for dead reckoning",
    description = "Lets Maps' dead reckoning use the head unit's gyro and the car's speed from the BYD vehicle SDK " +
        "(settings row \"Car data (dead reckoning)\"; the speed only where the BYD SDK gives one).",
    default = true,
) {
    compatibleWith(compatibility)
    dependsOn(automotiveMicroGPatch, carDataManifestPatch)

    execute {
        requireExtensionMethod(CAR_DATA, "defaultGyro", listOf("Landroid/hardware/SensorManager;", "I"), "Landroid/hardware/Sensor;")
        requireExtensionMethod(CAR_DATA, "gyroType", listOf("I"), "I")

        val inertial = inertialInput()

        // 1. getDefaultSensor(16) in the constructor.
        var routed = 0
        inertial.methods.filter { it.name == "<init>" }.forEach { constructor ->
            val code = constructor.implementation!!.instructions.toList()
            val sites = code.indices.filter { i ->
                code[i].opcode == Opcode.INVOKE_VIRTUAL && methodKey(code[i]) == GET_DEFAULT_SENSOR &&
                    literalBefore(code, i, (code[i] as FiveRegisterInstruction).registerD) == 16
            }
            val mutable = mutableMethodOf(constructor)
            sites.forEach { i ->
                val call = code[i] as FiveRegisterInstruction
                mutable.replaceInstruction(i, "invoke-static { v${call.registerC}, v${call.registerD} }, $CAR_DATA->defaultGyro(Landroid/hardware/SensorManager;I)Landroid/hardware/Sensor;")
                routed++
            }
        }
        if (routed != 1) throw PatchException("Expected one getDefaultSensor(16) in ${inertial.type}, found $routed")

        // 2. Every Sensor.getType() in the class.
        var types = 0
        inertial.methods.forEach { method ->
            val code = method.implementation?.instructions?.toList() ?: return@forEach
            val sites = code.indices.filter { i -> methodKey(code[i]) == GET_TYPE && code.getOrNull(i + 1)?.opcode == Opcode.MOVE_RESULT }
            if (sites.isEmpty()) return@forEach
            val mutable = mutableMethodOf(method)
            sites.sortedDescending().forEach { i ->
                val r = (code[i + 1] as OneRegisterInstruction).registerA
                mutable.addInstructions(i + 2, """
                    invoke-static/range { v$r .. v$r }, $CAR_DATA->gyroType(I)I
                    move-result v$r
                """)
                types++
            }
        }
        if (types < 2) throw PatchException("Expected getType() in onSensorChanged and onFlushCompleted of ${inertial.type}, found $types")
        gyroLogger.info("Car data gyro: ${inertial.type}, getDefaultSensor(16) routed, $types getType() site(s)")

        // 3. The automotive-feature check in front of Maps' car-service setup (speed listener among it):
        //    hasSystemFeature("android.hardware.type.automotive") -> CarData.carServiceFeature(result).
        requireExtensionMethod(CAR_DATA, "carServiceFeature", listOf("Z"), "Z")
        hookCarServiceFeature()

        // 4. Settings row "Car data (dead reckoning)": gyro and car speed switches.
        requireExtensionMethod(CAR_DATA, "getSettingsTitle", listOf("Landroid/content/Context;"), "Ljava/lang/String;")
        IncludedFeatures.settingsRows += CAR_DATA
    }
}

private const val CAR_SERVICE_SETUP = "CarEmbeddedServiceDelegate.<init> setup CarService dependencies"
private const val AUTOMOTIVE_FEATURE = "android.hardware.type.automotive"
private const val HAS_SYSTEM_FEATURE = "Landroid/content/pm/PackageManager;->hasSystemFeature(Ljava/lang/String;)Z"

private fun BytecodePatchContext.hookCarServiceFeature() {
    var patched = 0
    classDefForEach { classDef ->
        if (classDef.type.startsWith("Lapp/morphe/")) return@classDefForEach
        classDef.methods.forEach { method ->
            val code = method.implementation?.instructions?.toList() ?: return@forEach
            if (code.none { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == CAR_SERVICE_SETUP }) return@forEach
            val call = code.indices.firstOrNull { i ->
                methodKey(code[i]) == HAS_SYSTEM_FEATURE &&
                    (i - 4 until i).any { j -> j >= 0 && ((code[j] as? ReferenceInstruction)?.reference as? StringReference)?.string == AUTOMOTIVE_FEATURE }
            } ?: return@forEach
            val result = code.getOrNull(call + 1)
            if (result?.opcode != Opcode.MOVE_RESULT) return@forEach
            val r = (result as OneRegisterInstruction).registerA
            mutableMethodOf(method).addInstructions(call + 2, """
                invoke-static/range { v$r .. v$r }, $CAR_DATA->carServiceFeature(Z)Z
                move-result v$r
            """)
            patched++
            gyroLogger.info("Car data: automotive feature check in ${method.definingClass}->${method.name} (v$r)")
        }
    }
    if (patched != 1) throw PatchException("Expected one automotive-feature check in the car-service setup, patched $patched")
}

// The class with the yaw-only gyro string and a SensorEventListener2 interface.
private fun BytecodePatchContext.inertialInput(): ClassDef {
    val found = mutableListOf<ClassDef>()
    classDefForEach { classDef ->
        if (classDef.type.startsWith("Lapp/morphe/")) return@classDefForEach
        if ("Landroid/hardware/SensorEventListener2;" !in classDef.interfaces) return@classDefForEach
        val hasString = classDef.methods.any { m ->
            m.implementation?.instructions?.any { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == LIMITED_AXES_GYRO } == true
        }
        if (hasString) found += classDef
    }
    return found.singleOrNull() ?: throw PatchException("Expected one inertial input class, found ${found.joinToString { it.type }}")
}

private fun methodKey(ins: com.android.tools.smali.dexlib2.iface.instruction.Instruction): String? {
    val ref = (ins as? ReferenceInstruction)?.reference as? MethodReference ?: return null
    return "${ref.definingClass}->${ref.name}(${ref.parameterTypes.joinToString("")})${ref.returnType}"
}

// The literal last loaded into [register] before [index] (const/4, const/16, const), or null.
private fun literalBefore(code: List<com.android.tools.smali.dexlib2.iface.instruction.Instruction>, index: Int, register: Int): Int? {
    for (i in index - 1 downTo maxOf(0, index - 8)) {
        val ins = code[i]
        if (ins is OneRegisterInstruction && ins.registerA == register) {
            return if (ins is NarrowLiteralInstruction) ins.narrowLiteral else null
        }
    }
    return null
}
