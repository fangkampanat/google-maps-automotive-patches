package app.morphe.patches.mapsautomotive

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import java.util.logging.Logger

// On BYD DiLink and other uncertified head units, Android Automotive CarInfoManager is absent,
// causing Google Maps Automotive to default to Left-Hand Drive (LHD).
// This patch auto-detects RHD from vehicle properties (such as BYD's persist.sys.vehicle_rudder_info = 2)
// and adds a "Driving position" row in Maps' Settings so users can toggle between Auto, RHD, and LHD.

internal const val DRIVER_POSITION_CLASS =
    "Lapp/morphe/extension/mapsautomotive/patches/DriverPositionPatch;"
private val driverPositionLogger =
    Logger.getLogger("app.morphe.patches.mapsautomotive.DriverPositionPatch")

@Suppress("unused")
val driverPositionPatch = bytecodePatch(
    name = "Driving position (RHD / LHD)",
    description = "Selects right-hand drive or left-hand drive UI layouts, with auto-detection for BYD and other RHD vehicles.",
    default = true,
) {
    compatibleWith(compatibility)
    dependsOn(automotiveMicroGPatch)

    execute {
        hookDriverPosition()
        IncludedFeatures.settingsRows += DRIVER_POSITION_CLASS
        IncludedFeatures.features += "driver_position"
    }
}

private fun BytecodePatchContext.hookDriverPosition() {
    requireExtensionMethod(DRIVER_POSITION_CLASS, "isRightHandDrive", emptyList(), "Z")
    requireExtensionMethod(
        DRIVER_POSITION_CLASS,
        "getSettingsTitle",
        listOf("Landroid/content/Context;"),
        "Ljava/lang/String;",
    )
    requireExtensionMethod(
        DRIVER_POSITION_CLASS,
        "getSettingsSummary",
        listOf("Landroid/content/Context;"),
        "Ljava/lang/String;",
    )
    requireExtensionMethod(
        DRIVER_POSITION_CLASS,
        "getSettingsAction",
        listOf("Landroid/content/Context;", "Landroid/view/View;"),
        "Ljava/lang/Runnable;",
    )

    // Locate the Driver Position Enum (contains DRIVER_POSITION_LEFT, DRIVER_POSITION_RIGHT)
    var enumDef: ClassDef? = null
    classDefForEach { classDef ->
        if (classDef.type.startsWith("Lapp/morphe/extension/")) return@classDefForEach
        val strings = classDef.methods.flatMap { m ->
            m.implementation?.instructions?.mapNotNull {
                ((it as? ReferenceInstruction)?.reference as? StringReference)?.string
            } ?: emptyList()
        }.toSet()
        if ("DRIVER_POSITION_LEFT" in strings && "DRIVER_POSITION_RIGHT" in strings) {
            enumDef = classDef
        }
    }
    val targetEnum = enumDef ?: throw PatchException("Failed to find driver position enum class")

    // Find static fields 'b' (LEFT) and 'c' (RIGHT) in targetEnum
    val leftField = targetEnum.fields.firstOrNull { it.name == "b" && it.type == targetEnum.type }
        ?: throw PatchException("Missing DRIVER_POSITION_LEFT field 'b' in ${targetEnum.type}")
    val rightField = targetEnum.fields.firstOrNull { it.name == "c" && it.type == targetEnum.type }
        ?: throw PatchException("Missing DRIVER_POSITION_RIGHT field 'c' in ${targetEnum.type}")

    // Find instance methods returning targetEnum.type with 0 parameters
    var hookedCount = 0
    classDefForEach { classDef ->
        if (classDef.type.startsWith("Lapp/morphe/extension/")) return@classDefForEach
        classDef.methods.forEach { method ->
            if (method.implementation != null &&
                method.returnType == targetEnum.type &&
                method.parameterTypes.isEmpty() &&
                !AccessFlags.STATIC.isSet(method.accessFlags)
            ) {
                val mutable = mutableClassDefBy(method.definingClass).methods.single {
                    it.name == method.name && it.returnType == method.returnType && it.parameterTypes == method.parameterTypes
                }
                mutable.addInstructions(
                    0,
                    """
                        invoke-static {}, $DRIVER_POSITION_CLASS->isRightHandDrive()Z
                        move-result v0
                        if-nez v0, :morphe_rhd
                        sget-object v0, ${leftField.definingClass}->${leftField.name}:${leftField.type}
                        return-object v0
                        :morphe_rhd
                        sget-object v0, ${rightField.definingClass}->${rightField.name}:${rightField.type}
                        return-object v0
                    """.trimIndent(),
                )
                hookedCount++
                driverPositionLogger.info("Hooked driver position in $method")
            }
        }
    }
    if (hookedCount == 0) {
        throw PatchException("No driver position methods found to hook")
    }
    driverPositionLogger.info("Successfully hooked $hookedCount driver position methods (${leftField.name}/${rightField.name})")
}
