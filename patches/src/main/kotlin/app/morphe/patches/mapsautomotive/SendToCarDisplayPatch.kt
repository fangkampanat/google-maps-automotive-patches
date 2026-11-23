package app.morphe.patches.mapsautomotive

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import java.util.logging.Logger

// Maps' Send to car push handler opens a sent trip at once through its sent-trip helper (gup in
// 26.10.120003.E), whose start is startActivity(Intent, ActivityOptions.makeBasic().setLaunchDisplayId(0)
// .toBundle()) with a new-task intent (setLaunchDisplayId through an R8 API outline): the trip always lands on the main display. With Maps in a launcher's
// frame (a virtual display) that pulled Maps out of the frame, full screen. The setLaunchDisplayId call is
// replaced 1:1 by SendToCarDisplay.launchDisplay, which keeps 0 unless the last MapsActivity is on another
// display. The helper is found as in the Send to car patch: the handler asks for
// START_ACTIVITIES_FROM_BACKGROUND and calls a helper method returning an Intent. Required: one handler, one
// helper start with exactly one setLaunchDisplayId, or the patch fails.

private const val SEND_TO_CAR_DISPLAY_CLASS = "Lapp/morphe/extension/mapsautomotive/patches/SendToCarDisplay;"
private const val START_FROM_BACKGROUND = "android.permission.START_ACTIVITIES_FROM_BACKGROUND"
private const val ACTIVITY_OPTIONS = "Landroid/app/ActivityOptions;"
private const val INTENT_TYPE = "Landroid/content/Intent;"
private const val BUNDLE = "Landroid/os/Bundle;"
private val sendToCarDisplayLogger = Logger.getLogger("app.morphe.patches.mapsautomotive.SendToCarDisplayPatch")

@Suppress("unused")
val sendToCarDisplayPatch = bytecodePatch(
    name = "Send to car on Maps' display",
    description = "A trip sent from phone Maps opens on the display Maps is on, so Maps stays in a launcher's map " +
        "frame instead of jumping to the main display full screen.",
    default = true,
) {
    compatibleWith(compatibility)
    dependsOn(sendToCarPatch)

    execute {
        requireExtensionMethod(SEND_TO_CAR_DISPLAY_CLASS, "launchDisplay", listOf(ACTIVITY_OPTIONS, "I"), ACTIVITY_OPTIONS)
        val helper = sentTripHelperClass()
        val starts = mutableListOf<Method>()
        classDefForEach { classDef ->
            if (classDef.type != helper) return@classDefForEach
            classDef.methods.forEach { method ->
                val code = method.implementation?.instructions ?: return@forEach
                if (code.any { it.calls(null, "startActivity", listOf(INTENT_TYPE, BUNDLE)) } &&
                    code.any { it.setsLaunchDisplay() }
                ) starts += method
            }
        }
        val start = starts.singleOrNull()
            ?: throw PatchException("Expected one start on a launch display in $helper, found ${starts.map { it.name }}")
        val mutable = mutableMethodOf(start)
        val code = mutable.implementation!!.instructions
        val sites = code.indices.filter { code[it].setsLaunchDisplay() }
        val index = sites.singleOrNull()
            ?: throw PatchException("Expected one setLaunchDisplayId in $helper->${start.name}, found ${sites.size}")
        val call = code[index] as FiveRegisterInstruction
        if (call.registerCount != 2) {
            throw PatchException("Unexpected setLaunchDisplayId call in $helper->${start.name}")
        }
        mutable.replaceInstruction(
            index,
            "invoke-static { v${call.registerC}, v${call.registerD} }, " +
                "$SEND_TO_CAR_DISPLAY_CLASS->launchDisplay($ACTIVITY_OPTIONS I)$ACTIVITY_OPTIONS",
        )
        sendToCarDisplayLogger.info("Send to car display: $helper->${start.name} setLaunchDisplayId at $index replaced")
    }
}

// The sent-trip helper: the class of the Intent-returning method the push handler calls.
private fun BytecodePatchContext.sentTripHelperClass(): String {
    val helpers = mutableSetOf<String>()
    classDefForEach { classDef ->
        if (classDef.type.startsWith("Lapp/morphe/")) return@classDefForEach
        classDef.methods.forEach { method ->
            val code = method.implementation?.instructions ?: return@forEach
            if (code.none { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == START_FROM_BACKGROUND }) {
                return@forEach
            }
            code.forEach { instruction ->
                if (instruction.opcode != Opcode.INVOKE_VIRTUAL) return@forEach
                val reference = (instruction as ReferenceInstruction).reference as MethodReference
                if (reference.returnType == INTENT_TYPE && reference.definingClass != classDef.type &&
                    !reference.definingClass.startsWith("Landroid/")
                ) helpers += reference.definingClass
            }
        }
    }
    return helpers.singleOrNull() ?: throw PatchException("Expected one sent-trip helper, found $helpers")
}

// ActivityOptions.setLaunchDisplayId(int), called directly or through an R8 API outline: a static
// (ActivityOptions, int)ActivityOptions method of an ExternalSyntheticApiModelOutline class (hd's in 26.10.120003.E).
private fun com.android.tools.smali.dexlib2.iface.instruction.Instruction.setsLaunchDisplay(): Boolean {
    if (calls(ACTIVITY_OPTIONS, "setLaunchDisplayId", listOf("I"))) return true
    if (opcode != Opcode.INVOKE_STATIC) return false
    val reference = (this as ReferenceInstruction).reference as? MethodReference ?: return false
    return reference.definingClass.contains("ExternalSyntheticApiModelOutline") && reference.returnType == ACTIVITY_OPTIONS &&
        reference.parameterTypes.map { it.toString() } == listOf(ACTIVITY_OPTIONS, "I")
}

private fun com.android.tools.smali.dexlib2.iface.instruction.Instruction.calls(
    definingClass: String?,
    name: String,
    parameters: List<String>,
): Boolean {
    if (opcode != Opcode.INVOKE_VIRTUAL) return false
    val reference = (this as ReferenceInstruction).reference as? MethodReference ?: return false
    return (definingClass == null || reference.definingClass == definingClass) && reference.name == name &&
        reference.parameterTypes.map { it.toString() } == parameters
}
