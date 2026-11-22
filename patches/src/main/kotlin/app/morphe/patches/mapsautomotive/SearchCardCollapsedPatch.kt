package app.morphe.patches.mapsautomotive

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import java.util.logging.Logger

// The home screen's search card (search box, category buttons, suggested destinations) has a
// chevron that collapses it to the search box. Its state is a small interface of six methods
// (ird in 26.10.120003.E: reset()V, toggle()V, two (Z)V updates, collapsed()Z, expanded()Z) with two
// implementations, one per card layout (ire, iqu). Each keeps "collapsed" in its only boolean field,
// false when created and after a reset, so the card always opens expanded. The patch sets that field
// to true at the end of the constructor and of the reset method, so the card opens collapsed; the
// chevron still expands it. The card's view model (irg) holds three fields of the interface's type,
// which tells it apart from other interfaces of the same shape.
// Required anchors: a missing or ambiguous interface or an implementation of another shape fails the patch.

private val searchCardLogger = Logger.getLogger("app.morphe.patches.mapsautomotive.SearchCardCollapsedPatch")

@Suppress("unused")
val searchCardCollapsedPatch = bytecodePatch(
    name = "Search card starts collapsed",
    description = "The home screen's search card opens collapsed to the search box; the chevron expands it.",
    default = false,
) {
    compatibleWith(compatibility)

    execute {
        val state = searchCardStateInterface()
        val implementations = mutableListOf<ClassDef>()
        classDefForEach { classDef ->
            if (state.type in classDef.interfaces) implementations += classDef
        }
        if (implementations.size != 2) {
            throw PatchException("Expected 2 search card states implementing ${state.type}, found ${implementations.joinToString { it.type }}")
        }
        val resetName = state.methods.filter { it.returnType == "V" && it.parameterTypes.isEmpty() }
            .map { it.name }.sorted().first()
        implementations.forEach { impl ->
            val flags = impl.fields.filter { it.type == "Z" && !AccessFlags.STATIC.isSet(it.accessFlags) }
            val flag = flags.singleOrNull()
                ?: throw PatchException("Expected one boolean field in ${impl.type}, found ${flags.size}")
            val field = "${impl.type}->${flag.name}:Z"
            val constructor = impl.methods.singleOrNull { it.name == "<init>" && it.parameterTypes.isEmpty() }
                ?: throw PatchException("Expected a no-argument constructor in ${impl.type}")
            val reset = impl.methods.singleOrNull { it.name == resetName && it.returnType == "V" && it.parameterTypes.isEmpty() }
                ?: throw PatchException("Missing $resetName()V in ${impl.type}")
            setCollapsedBeforeReturns(constructor, field)
            setCollapsedBeforeReturns(reset, field)
            searchCardLogger.info("Search card state ${impl.type}: $field set true in <init> and $resetName()")
        }
    }
}

// The interface of the card state: six methods, ()V x2, (Z)V x2, ()Z x2, used as the type of at least
// three instance fields of one class (the card's view model).
private fun BytecodePatchContext.searchCardStateInterface(): ClassDef {
    val shape = listOf("()V", "()V", "()Z", "()Z", "(Z)V", "(Z)V")
    val candidates = mutableMapOf<String, ClassDef>()
    classDefForEach { classDef ->
        if (!AccessFlags.INTERFACE.isSet(classDef.accessFlags)) return@classDefForEach
        if (classDef.type.startsWith("Lapp/morphe/")) return@classDefForEach
        val methods = classDef.methods.toList()
        val signature = methods.map { "(" + it.parameterTypes.joinToString("") + ")" + it.returnType }.sorted()
        if (signature == shape) candidates[classDef.type] = classDef
    }
    val holders = mutableMapOf<String, MutableList<String>>()
    classDefForEach { classDef ->
        classDef.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type in candidates }
            .groupBy { it.type }
            .filter { it.value.size >= 3 }
            .forEach { (type, _) -> holders.getOrPut(type) { mutableListOf() } += classDef.type }
    }
    val matches = holders.filter { it.value.size == 1 }.keys
    val type = matches.singleOrNull()
        ?: throw PatchException("Expected one search card state interface, found ${matches.joinToString()} of ${candidates.keys.joinToString()}")
    searchCardLogger.info("Search card state interface $type, view model ${holders.getValue(type).single()}")
    return candidates.getValue(type)
}

private fun BytecodePatchContext.setCollapsedBeforeReturns(method: Method, field: String) {
    val implementation = method.implementation ?: throw PatchException("No code in $method")
    // Instance method without parameters: p0 is the last register, v0 is a local only when there are two.
    if (implementation.registerCount < 2) throw PatchException("No local register in $method")
    val returns = implementation.instructions.withIndex()
        .filter { it.value.opcode == Opcode.RETURN_VOID }
        .map { it.index }
    if (returns.isEmpty()) throw PatchException("No return-void in $method")
    val mutable = mutableMethodOf(method)
    returns.sortedDescending().forEach { index ->
        mutable.addInstructions(
            index,
            """
                const/4 v0, 0x1
                iput-boolean v0, p0, $field
            """,
        )
    }
}
