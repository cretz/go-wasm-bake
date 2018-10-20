package gowasmbake

import asmble.ast.Node
import asmble.run.jvm.interpret.Imports
import asmble.run.jvm.interpret.Interpreter
import asmble.util.Either
import asmble.util.Logger
import asmble.util.toUnsignedLong
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.time.Instant
import java.util.*

class GoRuntime(
    mod: Node.Module,
    logger: Logger = Logger.Print(Logger.Level.TRACE),
    interpreter: Interpreter = Interpreter.Companion,
    val syscallRecorder: ((JsSyscall) -> Unit)? = null
) : Imports {
    var ctx = Interpreter.Context(
        mod = mod,
        logger = logger,
        interpreter = interpreter,
        imports = this,
        defaultMaxMemPages = 16384
    )

    protected var random: Random = SecureRandom()
    protected var out: OutputStream? = System.out

    protected var debug: ((Int) -> Unit)? = { logger.debug { "WASM debug: $it" } }
    protected var exitCode: Int? = null
    protected var callbackPromiseResolved = false
    val values = mutableListOf(
        JsValue.JsNumber(Double.NaN),
        JsValue.JsUndefined,
        JsValue.JsNull,
        JsValue.JsBoolean(true),
        JsValue.JsBoolean(false),
        // Global
        JsValue.JsObject.Simple(
            "Array" to JsValue.JsObject.Func {
                require(it.isEmpty()) { "Only empty for now" }
                JsValue.JsArray()
            },
            "Object" to JsValue.JsObject.Func { TODO("Obj Args: $it") },
            "Go" to JsValue.JsObject.Simple(
                "_makeCallbackHelper" to JsValue.JsObject.Func { (id, pendingCallbacks, go) ->
                    pendingCallbacks as JsValue.JsArray
                    go as JsValue.JsObject.Simple
                    JsValue.JsObject.Func { args ->
                        pendingCallbacks.v += JsValue.JsObject.Simple(
                            "id" to id,
                            "args" to JsValue.JsArray(args.toMutableList())
                        )
                        (go.v["_resolveCallbackPromise"] as JsValue.JsObject.Func).v(emptyList())
                    }
                },
                "_makeEventCallbackHelper" to JsValue.JsObject.Func {
                    TODO()
                },
                "_resolveCallbackPromise" to JsValue.JsObject.Func {
                    callbackPromiseResolved = true
                    JsValue.JsUndefined
                }
            ),
            "Int8Array" to JsValue.JsObject.Func { TODO() },
            "Int16Array" to JsValue.JsObject.Func { TODO() },
            "Int32Array" to JsValue.JsObject.Func { TODO() },
            "Uint8Array" to JsValue.JsObject.Func { TODO() },
            "Uint16Array" to JsValue.JsObject.Func { TODO() },
            "Uint32Array" to JsValue.JsObject.Func { TODO() },
            "Float32Array" to JsValue.JsObject.Func { TODO() },
            "Float64Array" to JsValue.JsObject.Func { TODO() },
            "process" to JsValue.JsObject.Simple(),
            "fs" to JsValue.JsObject.Simple(
                "constants" to JsValue.JsObject.Simple(
                    "O_WRONLY" to JsValue.JsNumber(-1),
                    "O_RDWR" to JsValue.JsNumber(-1),
                    "O_CREAT" to JsValue.JsNumber(-1),
                    "O_TRUNC" to JsValue.JsNumber(-1),
                    "O_APPEND" to JsValue.JsNumber(-1),
                    "O_EXCL" to JsValue.JsNumber(-1)
                ),
                "writeSync" to JsValue.JsObject.Func { TODO() },
                "openSync" to JsValue.JsObject.Func { TODO() }
            )
        ),
        // Mem
        JsValue.JsObject.Simple(),
        // This
        JsValue.JsObject.Simple()
    )

    val refs = IdentityHashMap<JsValue, Int>()

    fun run(vararg args: String) = run(args, emptyMap())

    fun run(args: Array<out String>, env: Map<String, String>): Int? {
        // Argc + argv, then env appended to argv as key=val
        var offset = 4096
        val argc = args.size
        val strPtrs = ArrayList<Int>()
        for (arg in args) {
            strPtrs.add(offset)
            offset += newString(arg, offset)
        }
        for ((key, value) in env) {
            strPtrs.add(offset)
            offset += newString("$key=$value", offset)
        }
        val argv = offset
        for (strPtr in strPtrs) {
            ctx.mem.putLong(offset, strPtr.toLong())
            offset += 8
        }
        // Run and return exit code
        ctx.interpreter.execFunc(ctx, ctx.exportIndex("run", Node.ExternalKind.FUNCTION)!!, argc, argv)
        return exitCode
    }

    // Returns size, aligned to 8
    protected fun newString(str: String, ptr: Int): Int {
        val bytes = (str + '\u0000').toByteArray(StandardCharsets.UTF_8)
        putBytes(ptr, bytes)
        return bytes.size + (8 - bytes.size % 8)
    }

    protected fun getBytes(offset: Int, bytes: ByteArray): ByteArray {
        val buf = ctx.mem.duplicate()
        buf.position(offset)
        buf.get(bytes)
        return bytes
    }

    protected fun putBytes(offset: Int, bytes: ByteArray) {
        val buf = ctx.mem.duplicate()
        buf.position(offset)
        buf.put(bytes)
    }

    protected fun getInt64(addr: Int) =
        ctx.mem.getInt(addr).toUnsignedLong() + (ctx.mem.getInt(addr + 4).toUnsignedLong() * 4294967296)

    protected fun putUInt8(addr: Int, v: Int) = ctx.mem.put(addr, (v and 0xff).toByte())
    protected fun getUInt32(addr: Int) = ctx.mem.getInt(addr).toUnsignedLong()
    protected fun putUInt32(addr: Int, v: Long) = ctx.mem.putInt(addr, (v and 0xffffffffL).toInt())

    // Returns true if it had to store a new value
    protected fun storeValue(addr: Int, v: JsValue): Boolean {
        val nanHead = 0x7FF80000L
        var newValue = false
        when (v) {
            is JsValue.JsNull -> {
                putUInt32(addr + 4, nanHead)
                putUInt32(addr, 2)
            }
            is JsValue.JsUndefined -> {
                putUInt32(addr + 4, nanHead)
                putUInt32(addr, 1)
            }
            is JsValue.JsNumber -> v.v.toDouble().also { v ->
                if (v.isNaN()) {
                    putUInt32(addr + 4, nanHead)
                    putUInt32(addr, 0)
                } else ctx.mem.putDouble(addr, v)
            }
            is JsValue.JsString, is JsValue.JsObject, is JsValue.JsArray -> {
                val ref = refs.getOrPut(v) { values.apply { newValue = true; add(v) }.lastIndex }
                val typeFlag = when (v) {
                    is JsValue.JsString -> 1L
                    is JsValue.JsObject.Func -> 3L
                    else -> 2L
                }
                ctx.logger.debug { "Storing as value #$ref: $v" }
                putUInt32(addr + 4, nanHead or typeFlag)
                putUInt32(addr, ref.toLong())
            }
        }
        return newValue
    }

    // Returns object and the value array index if any
    protected fun loadValue(addr: Int): Pair<JsValue, Int?> {
        // Try double first
        ctx.mem.getDouble(addr).also { if (!it.isNaN()) return JsValue.JsNumber(it) to null }
        val valueIndex = getUInt32(addr).toInt().also { ctx.logger.debug { "Asking for value #$it" } }
        return values[valueIndex] to valueIndex
    }

    protected fun loadSliceOfValues(addr: Int): List<Pair<JsValue, Int?>> {
        val arr = getInt64(addr).toInt()
        val len = getInt64(addr + 8).toInt()
        return (0 until len).map { loadValue(arr + (it * 8)) }
    }

    protected fun loadString(addr: Int) =
        ByteArray(getInt64(addr + 8).toInt()).also { getBytes(getInt64(addr).toInt(), it) }.toString(Charsets.UTF_8)

    override fun getGlobal(module: String, field: String, type: Node.Type.Global) = TODO()
    override fun setGlobal(module: String, field: String, type: Node.Type.Global, value: Number) = TODO()
    override fun getMemory(module: String, field: String, type: Node.Type.Memory) = TODO()
    override fun getTable(module: String, field: String, type: Node.Type.Table) = TODO()

    override fun invokeFunction(module: String, field: String, type: Node.Type.Func, args: List<Number>) =
        (args.singleOrNull() as? Int ?: error("Expected single int arg, got $args")).let { sp ->
            require(module == "go") { "Expected module 'go', got '$module'" }
            ctx.logger.debug { "Invoking: $module.$field" }
            when (field) {
                "debug" -> debug(sp)
                "runtime.wasmExit" -> runtimeWasmExit(sp)
                "runtime.wasmWrite" -> runtimeWasmWrite(sp)
                "runtime.nanotime" -> runtimeNanotime(sp)
                "runtime.walltime" -> runtimeWalltime(sp)
                "runtime.scheduleCallback" -> runtimeScheduleCallback(sp)
                "runtime.clearScheduledCallback" -> runtimeClearScheduledCallback(sp)
                "runtime.getRandomData" -> runtimeGetRandomData(sp)
                "syscall/js.stringVal" -> jsStringVal(sp)
                "syscall/js.valueGet" -> jsValueGet(sp)
                "syscall/js.valueSet" -> jsValueSet(sp)
                "syscall/js.valueSetIndex" -> jsValueSetIndex(sp)
                "syscall/js.valueCall" -> jsValueCall(sp)
                "syscall/js.valueInvoke" -> jsValueInvoke(sp)
                "syscall/js.valueNew" -> jsValueNew(sp)
                "syscall/js.valueLength" -> jsValueLength(sp)
                "syscall/js.valuePrepareString" -> jsValuePrepareString(sp)
                "syscall/js.valueLoadString" -> jsValueLoadString(sp)
                "syscall/js.valueInstanceOf" -> jsValueInstanceOf(sp)
                else -> error("Unrecognized function $module.$field")
            }
            null
        }

    protected fun debug(v: Int) {
        debug?.invoke(v)
    }

    protected fun runtimeWasmExit(sp: Int) {
        wasmExit(ctx.mem.getInt(sp + 8))
    }

    protected fun wasmExit(exitCode: Int) {
        this.exitCode = exitCode
    }

    protected fun runtimeWasmWrite(sp: Int) {
        val fd = ctx.mem.getLong(sp + 8)
        val ptr = ctx.mem.getLong(sp + 16)
        val len = ctx.mem.getInt(sp + 24)
        wasmWrite(fd, getBytes(ptr.toInt(), ByteArray(len)))
    }

    protected fun wasmWrite(fd: Long, bytes: ByteArray) {
        if (fd != 2L) throw UnsupportedOperationException("Only fd 2 support on write, got $fd")
        out?.write(bytes)
    }

    protected fun runtimeNanotime(sp: Int) {
        ctx.mem.putLong(sp + 8, System.nanoTime())
    }

    protected fun runtimeWalltime(sp: Int) {
        val now = Instant.now()
        ctx.mem.putLong(sp + 8, now.epochSecond)
        ctx.mem.putInt(sp + 16, now.nano)
    }

    protected fun runtimeScheduleCallback(sp: Int) {
        TODO("runtime.scheduleCallback")
    }

    protected fun runtimeClearScheduledCallback(sp: Int) {
        TODO("runtime.clearScheduledCallback")
    }

    protected fun runtimeGetRandomData(sp: Int) {
        val len = ctx.mem.getLong(sp + 16)
        val bytes = ByteArray(len.toInt())
        random.nextBytes(bytes)
        putBytes(ctx.mem.getLong(sp + 8).toInt(), bytes)
    }

    protected fun jsStringVal(sp: Int) {
        TODO("jsStringVal")
    }

    protected fun jsValueGet(sp: Int) {
        val (v, valueIndex) = loadValue(sp + 8)
        v as? JsValue.JsObject.Simple ?: error("Not object: $v")
        val propName = loadString(sp + 16)
        ctx.logger.debug { "Asking for property $propName" }
        val newValue = storeValue(sp + 32, v.v[propName] ?: error("No value for $propName"))
        syscallRecorder?.apply {
            invoke(JsSyscall.Get(
                sp = sp,
                valueIndex = valueIndex!!,
                property = propName,
                resultIsNewValue = newValue
            ))
        }
    }

    protected fun jsValueSet(sp: Int) {
        TODO("jsValueSet")
    }

    protected fun jsValueSetIndex(sp: Int) {
        TODO("jsValueSetIndex")
    }

    protected fun jsValueCall(sp: Int) {
        TODO("jsValueCall")
    }

    protected fun jsValueInvoke(sp: Int) {
        TODO("jsValueInvoke")
    }

    protected fun jsValueNew(sp: Int) {
        val (v, valueIndex) = loadValue(sp + 8)
        v as? JsValue.JsObject.Func ?: error("Not function: $v")
        val args = loadSliceOfValues(sp + 16).also { ctx.logger.debug { "New with args: $it" } }
        val newValue = storeValue(sp + 40, v.v(args.map { it.first }))
        putUInt8(sp + 48, 1)
        syscallRecorder?.apply {
            invoke(JsSyscall.New(
                sp = sp,
                valueIndex = valueIndex!!,
                argValueIndices = args.map { (v, index) ->
                    if (index == null) Either.Left((v as JsValue.JsNumber).v.toDouble()) else Either.Right(index)
                },
                resultIsNewValue = newValue
            ))
        }
    }

    protected fun jsValueLength(sp: Int) {
        TODO("jsValueLength")
    }

    protected fun jsValuePrepareString(sp: Int) {
        TODO("jsValuePrepareString")
    }

    protected fun jsValueLoadString(sp: Int) {
        TODO("jsValueLoadString")
    }

    protected fun jsValueInstanceOf(sp: Int) {
        TODO("jsValueInstanceOf")
    }

    sealed class JsValue {
        object JsNull : JsValue()
        object JsUndefined : JsValue()
        data class JsNumber(val v: Number) : JsValue()
        data class JsString(val v: String) : JsValue()
        data class JsBoolean(val v: Boolean) : JsValue()
        sealed class JsObject : JsValue() {
            data class Simple(val v: MutableMap<String, JsValue>) : JsObject() {
                constructor(vararg v: Pair<String, JsValue>) : this(mutableMapOf(*v))
            }
            data class Func(val v: (List<JsValue>) -> JsValue) : JsObject()
            data class Opaque(val v: Any?) : JsObject()
        }
        data class JsArray(val v: MutableList<JsValue> = mutableListOf()) : JsValue()
    }

    sealed class JsSyscall {
        abstract val sp: Int
        data class Get(
            override val sp: Int,
            val valueIndex: Int,
            val property: String,
            val resultIsNewValue: Boolean
        ) : JsSyscall()
        data class New(
            override val sp: Int,
            val valueIndex: Int,
            val argValueIndices: List<Either<Double, Int>>,
            val resultIsNewValue: Boolean
        ) : JsSyscall()
    }
}