package gowasmbake

import asmble.io.AstToBinary
import asmble.io.BinaryToAst
import asmble.util.Logger
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.file
import java.io.File
import kotlin.system.measureTimeMillis

class Main : CliktCommand(name = "go-wasm-bake") {
    val source by argument().file(exists = true)
    val dest by argument().file()
    val logLevel by option(help = "Default 'info'").
        choice(*Logger.Level.values().map { it.name.toLowerCase() }.toTypedArray()).default("info")
    val arg by option(help = "First is prog name, unset is DEST").multiple()
    val env by option(help = "Name and val separated by first '='").multiple()

    override fun run() {
        val logger = Logger.Print(Logger.Level.valueOf(logLevel.toUpperCase()))
        val origWasm = source.readBytes()
        val origMod = BinaryToAst.toModule(source.readBytes())

        val envMap = env.map { str ->
            str.indexOf('=').let { if (it == -1) str to "" else str.substring(0, it) to str.substring(it + 1) }
        }.toMap()
        logger.info { "Starting bake on $source with args $arg" }
        var ms = System.currentTimeMillis()
        val baked = Baker(logger).bake(origMod, arg, envMap)
        ms = System.currentTimeMillis() - ms
        val bakedWasm = AstToBinary.fromModule(baked.mod)

        // Bake it a second time just to get insns saved
        val twiceBaked = Baker(logger).bake(baked.mod, arg, envMap)

        logger.info {
            val sizePct = (((origWasm.size - bakedWasm.size) / origWasm.size.toDouble()) * 100).toInt()
            "Baked WASM in $ms ms, went from ${origWasm.size} to ${bakedWasm.size} bytes ($sizePct% smaller)"
        }
        logger.debug { "Cleared out the code for the following inits: ${baked.initsCleared.sorted()}" }
        logger.debug { "Abstracted ${baked.abstractedPreCalls} pre-calls" }
        logger.info {
            "Went from ${baked.origInsnCountUntilMain} insns until main.main to ${twiceBaked.origInsnCountUntilMain}"
        }
        logger.info { "Saving to $dest" }
        dest.writeBytes(bakedWasm)
        logger.info { "JS required before go.run call in JS:\n-------------------\n${baked.jsSyscallCode()}" }
    }
}

fun main(args: Array<String>) = Main().main(args)