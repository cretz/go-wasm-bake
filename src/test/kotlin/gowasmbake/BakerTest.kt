package gowasmbake

import asmble.io.AstToBinary
import asmble.io.BinaryToAst
import asmble.util.Logger
import org.junit.Assert
import org.junit.Test

class BakerTest {
    val logger = Logger.Print(Logger.Level.INFO)

    @Test
    fun test() {
        // Load mod
        val origWasm = readHelloWorldWasm()
        val origMod = BinaryToAst.toModule(origWasm)
        // Bake
        var ms = System.currentTimeMillis()
        val baked = Baker(logger).bake(origMod, listOf("foo", "bar"), emptyMap())
        ms = System.currentTimeMillis() - ms
        val bakedWasm = AstToBinary.fromModule(baked.mod)
        // Make sure there are some syscalls, unicode.init got invoked, and there were pre-calls abstracted
        Assert.assertTrue(baked.syscalls.isNotEmpty())
        Assert.assertTrue(baked.initsCleared.contains("unicode.init"))
        Assert.assertTrue(baked.abstractedPreCalls > 0)
        // Bake again just because
        println("Second...")
        val twiceBaked = Baker(logger).bake(baked.mod, listOf("foo", "bar"), emptyMap())
        // Make sure there are no syscalls before main, no inits called, and there were no pre-calls abstracted
        Assert.assertTrue(twiceBaked.syscalls.isEmpty())
        Assert.assertTrue(twiceBaked.initsCleared.isEmpty())
        Assert.assertTrue(twiceBaked.abstractedPreCalls == 0)
        // Print out a summary
        logger.info { "Baked WASM in $ms ms, went from ${origWasm.size} to ${bakedWasm.size} bytes" }
        logger.info { "Cleared out the code for the following inits: ${baked.initsCleared.sorted()}" }
        logger.info { "Abstracted ${baked.abstractedPreCalls} pre-calls" }
        logger.info {
            "Went from ${baked.origInsnCountUntilMain} insns until main.main to ${twiceBaked.origInsnCountUntilMain}"
        }
    }

    fun readHelloWorldWasm(): ByteArray {
        // Write the Go to a temp file, build it to a temp wasm, read bytes and delete both
        val tempGoFile = createTempFile(suffix = ".go")
        val tempWasmFile = createTempFile(suffix = ".wasm")
        try {
            tempGoFile.writeBytes(BakerTest::class.java.getResource("/hello-world.go").readBytes())
            tempWasmFile.delete()
            val procBld = ProcessBuilder("go", "build", "-o", tempWasmFile.absolutePath, tempGoFile.absolutePath)
            procBld.inheritIO().environment().apply {
                put("GOOS", "js")
                put("GOARCH", "wasm")
            }
            require(procBld.start().waitFor() == 0)
            return tempWasmFile.readBytes()
        } finally {
            tempGoFile.delete()
            tempWasmFile.delete()
        }
    }
}