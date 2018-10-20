package gowasmbake

import asmble.ast.Node
import asmble.run.jvm.interpret.Interpreter
import asmble.util.Either
import asmble.util.Logger
import asmble.util.get

class Baker(val logger: Logger) {
    fun bake(mod: Node.Module, args: List<String>, env: Map<String, String>): Baked {
        // Run until break
        val info = runUntilBreak(mod, args, env)
        // Bake and return
        return bakeWithInfo(info)
    }

    fun runUntilBreak(mod: Node.Module, args: List<String>, env: Map<String, String>): BreakInfo {
        // Grab the indices of the three important functions for us
        var runtimeStartIndex = 0
        var runtimeMainIndex = 0
        var mainMainIndex = 0
        var runtimePauseIndex = 0
        val allInitFuncs = mutableSetOf<Int>()
        mod.names?.funcNames?.forEach { index, funcName ->
            when (funcName) {
                "_rt0_wasm_js" -> runtimeStartIndex = index
                "runtime.main" -> runtimeMainIndex = index
                "runtime.pause" -> runtimePauseIndex = index
                "main.main" -> mainMainIndex = index
                else -> if (funcName.endsWith(".init")) allInitFuncs += index
            }
        }
        require(runtimeStartIndex != 0 && runtimeMainIndex != 0 && runtimePauseIndex != 0 && mainMainIndex != 0)
        // During interpret, store syscalls, inits, and the latest resume point
        val syscalls = mutableListOf<GoRuntime.JsSyscall>()
        val initCalls = mutableSetOf<Int>()
        var resumePoint = 0
        var insnCount = 0
        // Build the runtime with some interpreter overrides to grab values we need
        val runtime = GoRuntime(
            mod = mod,
            logger = logger,
            interpreter = object : Interpreter() {
                override fun next(ctx: Context, step: StepResult) {
                    if (step is StepResult.Call) {
                        // Hit main, stop
                        if (step.funcIndex == mainMainIndex) throw ReachedBreakFunc()
                        // Calling an init, mark it
                        if (allInitFuncs.contains(step.funcIndex)) initCalls += step.funcIndex
                    }
                    super.next(ctx, step)
                }

                override fun invokeSingle(ctx: Context): StepResult {
                    insnCount++
                    // Setting global 1 (PC_B) is setting the resume point
                    if (ctx.currFuncCtx.funcIndex == runtimeMainIndex) {
                        val insn = ctx.currFuncCtx.func.instructions[ctx.currFuncCtx.insnIndex]
                        if (insn is Node.Instr.SetGlobal && insn.index == 1) {
                            ctx.currFuncCtx.peek().also { if (it is Int && it > 0) resumePoint = it }
                        }
                    }
                    return super.invokeSingle(ctx)
                }
            },
            syscallRecorder = syscalls::plusAssign
        )
        // Find the elem index of runtime.main
        var runtimeMainElemIndex = 0
        for (elem in mod.elems) {
            val mainIndex = elem.funcIndices.indexOf(runtimeMainIndex)
            if (mainIndex >= 0) {
                runtimeMainElemIndex = ((runtime.ctx.singleConstant(elem.offset) ?: 0) as Int) + mainIndex
                break
            }
        }
        require(runtimeMainElemIndex > 0)
        // Run it until we reached break point
        try {
            runtime.run(args.toTypedArray(), env)
            error("Never reached main.main")
        } catch (e: ReachedBreakFunc) { }
        logger.debug {
            "Broke at call stack:" + runtime.ctx.callStack.map {
                "\n  ${runtime.ctx.mod.names?.funcNames?.get(it.funcIndex)}(#${it.funcIndex}):${it.insnIndex}"
            }
        }
        logger.debug { "Broke at block stack:" + runtime.ctx.currFuncCtx.blockStack.map { "\n  $it" } }
        logger.debug { "Broke at value stack:" + runtime.ctx.currFuncCtx.valueStack }
        logger.debug { "Broke with ref set:" + runtime.refs.map { (k, v) -> "\n  $k -> $v" } }
        logger.debug { "Broke with value set:" + runtime.values.mapIndexed { index, v -> "\n  $index -> $v" } }
        // Do validation
        if (runtime.ctx.callStack.map { it.funcIndex } != listOf(runtimeStartIndex, runtimeMainIndex))
            error("Invalid call stack, not inside runtime main")
        if (runtime.ctx.currFuncCtx.valueStack.isNotEmpty())
            error("Stack expected to be empty at break")

        // Run the pause command
        runtime.ctx.interpreter.execFunc(runtime.ctx, runtimePauseIndex)

        return BreakInfo(runtime.ctx, syscalls, initCalls,
            runtimeStartIndex, runtimeMainElemIndex, resumePoint, insnCount)
    }

    fun bakeWithInfo(info: BreakInfo): Baked {
        var mod = info.ctx.mod
        mod = bakeGlobals(info, mod)
        mod = bakeData(info, mod)
        mod = clearInits(info, mod)
        mod = makeSyscalls(info, mod)
        mod = removeUnusedLocals(mod)
        val preCallCount = abstractPreCall(mod).let { (newMod, count) -> mod = newMod; count }
        return Baked(
            mod = mod,
            syscalls = info.syscalls,
            origInsnCountUntilMain = info.insnCountUntilMain,
            initsCleared = info.initsInvoked.mapNotNull { mod.names?.funcNames?.get(it) }.toSet(),
            abstractedPreCalls = preCallCount
        )
    }

    fun bakeGlobals(info: BreakInfo, mod: Node.Module) = mod.copy(
        // Update with current values and a couple of overrides
        globals = mod.globals.mapIndexed { index, global ->
            val init: Node.Instr = when (index) {
                // Set resume point to main.main
                1 -> Node.Instr.I32Const(info.runtimeMainResumePoint)
                // Set all others to their value
                else -> when (global.type.contentType) {
                    is Node.Type.Value.I32 -> Node.Instr.I32Const(info.ctx.moduleGlobals[index] as Int)
                    is Node.Type.Value.I64 -> Node.Instr.I64Const(info.ctx.moduleGlobals[index] as Long)
                    is Node.Type.Value.F32 -> Node.Instr.F32Const(info.ctx.moduleGlobals[index] as Float)
                    is Node.Type.Value.F64 -> Node.Instr.F64Const(info.ctx.moduleGlobals[index] as Double)
                }
            }
            logger.debug { "Setting global $index to $init" }
            global.copy(init = listOf(init))
        }
    )

    fun bakeData(info: BreakInfo, mod: Node.Module): Node.Module {
        // Find all non-zero byte ranges. Combine ones that may have up to X zeros within
        val maxZerosInData = 5
        var byteIndex = 0
        val ranges = mutableListOf<IntRange>()
        while (byteIndex < info.ctx.mem.limit()) {
            // Find next start (i.e. non-zero)
            while (byteIndex < info.ctx.mem.limit() && info.ctx.mem.get(byteIndex) == 0.toByte()) byteIndex++
            if (byteIndex == info.ctx.mem.limit()) break
            val startIndex = byteIndex
            // Find next end (i.e. zero)
            while (byteIndex < info.ctx.mem.limit() && info.ctx.mem.get(byteIndex) != 0.toByte()) byteIndex++
            // Add or update last range
            if (ranges.isEmpty() || ranges.last().endInclusive + maxZerosInData < startIndex)
                ranges += startIndex until byteIndex
            else ranges[ranges.lastIndex] = ranges.last().start until byteIndex
        }
        // Set data
        return mod.copy(
            data = ranges.map { range ->
                logger.debug { "Setting byte range $range" }
                Node.Data(
                    index = 0,
                    offset = listOf(Node.Instr.I32Const(range.start)),
                    data = ByteArray(range.endInclusive - range.start + 1).also { info.ctx.mem.get(range.start, it) }
                )
            }
        )
    }

    fun clearInits(info: BreakInfo, mod: Node.Module) = mod.copy(
        // Just change all seen inits to return 0 and be done
        funcs = mod.funcs.mapIndexed { index, func ->
            if (!info.initsInvoked.contains(index - info.ctx.importFuncs.size)) func
            else func.copy(instructions = listOf(Node.Instr.I32Const(0)))
        }
    )

    fun makeSyscalls(info: BreakInfo, mod: Node.Module): Node.Module {
        // TODO: we could, in the first function, make all the syscalls in WASM here instead of JS
        return mod
    }

    fun removeUnusedLocals(mod: Node.Module) = mod.copy(
        funcs = mod.funcs.map { func ->
            val maxLocalUsed = func.instructions.mapNotNull {
                when (it) {
                    is Node.Instr.GetLocal -> it.index
                    is Node.Instr.SetLocal -> it.index
                    is Node.Instr.TeeLocal -> it.index
                    else -> null
                }
            }.max() ?: -1
            func.copy(locals = func.locals.take((maxLocalUsed + 1) - func.type.params.size))
        }
    )

    fun abstractPreCall(mod: Node.Module): Pair<Node.Module, Int> {
        // Ignore if already present
        if (mod.names?.funcNames?.containsValue("_preCall") == true) {
            logger.debug { "Skipping pre-call abstraction, function already present" }
        }
        // This is a common set of 9 insns that are called a lot with only 1 unique constant. It
        // is currently inlined in Go, see https://github.com/golang/go/issues/26622#issuecomment-408778990
        val preCall = Node.Func(
            type = Node.Type.Func(listOf(Node.Type.Value.I64), null),
            locals = emptyList(),
            instructions = listOf(
                Node.Instr.GetGlobal(2),
                Node.Instr.I32Const(8),
                Node.Instr.I32Sub,
                Node.Instr.SetGlobal(2),
                Node.Instr.GetGlobal(2),
                Node.Instr.GetLocal(0),
                Node.Instr.I64Store(3, 0),
                Node.Instr.I32Const(0),
                Node.Instr.SetGlobal(1)
            )
        )
        val preCallIndex = mod.imports.count { it.kind is Node.Import.Kind.Func } + mod.funcs.size
        var count = 0
        return mod.copy(
            names = mod.names?.let { it.copy(funcNames = it.funcNames + (preCallIndex to "_preCall")) },
            funcs = mod.funcs.map { func ->
                func.copy(
                    instructions = ArrayList<Node.Instr>(func.instructions.size).also { newInsns ->
                        var index = 0
                        while (index < func.instructions.size) {
                            // Match all but the 6th which is a const and the actual param
                            var constVal: Node.Instr.I64Const? = null
                            for (i in 0..8) {
                                // 6th is an i64 const we extract, rest have to be expected insns
                                if (i == 5) constVal = func.instructions[index + 5] as? Node.Instr.I64Const ?: break
                                else if (preCall.instructions[i] == func.instructions[index + i]) continue
                                else { constVal = null; break }
                            }
                            // If it's found, place the abstraction call and incr the index
                            if (constVal == null) newInsns += func.instructions[index] else {
                                count++
                                newInsns += constVal
                                newInsns += Node.Instr.Call(preCallIndex)
                                index += 8
                            }
                            index++
                        }
                    }
                )
            } + preCall
        ) to count
    }

    class ReachedBreakFunc : RuntimeException()

    data class BreakInfo(
        val ctx: Interpreter.Context,
        val syscalls: List<GoRuntime.JsSyscall>,
        val initsInvoked: Set<Int>,
        val runtimeStartFuncIndex: Int,
        val runtimeMainIndirectElemIndex: Int,
        val runtimeMainResumePoint: Int,
        val insnCountUntilMain: Int
    )

    data class Baked(
        val mod: Node.Module,
        val syscalls: List<GoRuntime.JsSyscall>,
        val origInsnCountUntilMain: Int,
        val initsCleared: Set<String>,
        val abstractedPreCalls: Int
    ) {
        fun jsSyscallCode(): String {
            val putRefConst = """
                const putRef = (v) => {
                  const nanHead = 0x7FF80000;
                  if (v === undefined || v === null || v === true || v === false || typeof v === 'number') return;
                  if (!this._refs.has(v)) {
                    const ref = this._values.length;
                    this._values.push(v);
                    this._refs.set(v, ref);
                  }
                }
            """.trimIndent()
            return syscalls.fold(putRefConst) { str, syscall ->
                when (syscall) {
                    is GoRuntime.JsSyscall.Get ->
                        str + "\nputRef(Reflect.get(this._values[${syscall.valueIndex}], '${syscall.property}'));"
                    is GoRuntime.JsSyscall.New -> {
                        val args = syscall.argValueIndices.joinToString {
                            when (it) {
                                is Either.Left -> it.v.toString()
                                is Either.Right -> "this._values[${it.v}]"
                            }
                        }
                        str + "\nputRef(Reflect.construct(this._values[${syscall.valueIndex}], [$args]));"
                    }
                }
            }
        }
    }
}