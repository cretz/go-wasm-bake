# GoWasmBake

GoWasmBake is a proof of concept for reducing runtime costs of WASM files generated by Go 1.11. It basically runs all
the Go code up until `main.main`, bakes in that runtime information (e.g. memory data, JS syscalls, etc) in a new WASM
environment, makes the new WASM environment jump right to `main.main`, and does a couple of other minor WASM trimming
tricks.

**NOTE: As a proof of concept, it is only tested with hello world and may not work in most cases.**
**This is just an experiment and should not be used in production.**
**See "Caveats" in the "How it Works" section.**

## Results

On the simple hello world example from the next section, GoWasmBake achieves the following:

* Saved ~17% of the file size (2,445,309 bytes to 2,022,376 bytes)
* Skipped over 4,205,101 instructions pre-`main.main` instructions (not all are executable instructions of course)
* Skipped over and removed code for 13 package init functions
* Took 11,649 instances of a common 9-insn pattern and changed each to a 2-insn call (i.e. inline expansion)

Measurements have not been done on the following and would be welcomed by anyone willing to do them:

* Performance impact of exchanging all those startup instructions for fixed memory data
* GZIP'd impact of the final file

## Usage and Example

Prerequisites:

* Go 1.11 installed and on the `PATH`
* Java 1.8+ installed and on the `PATH`
* Latest Node.js installed and on the `PATH`
* GoWasmBake [latest release](https://github.com/cretz/go-wasm-bake/releases) extracted with `bin/` on the `PATH`

First, make a simple `hello-world.go` file containing:

```go
package main

import (
	"fmt"
	"os"
)

func main() {
	fmt.Printf("From %v - Hello, %v!", os.Args[0], os.Args[1])
}
```

Set environment variable `GOOS` to `js` and `GOARCH` to `wasm`. Then compile `hello-world.go` to
`hello-world.orig.wasm`:

    go build -o hello-world.orig.wasm hello-world.go

The WASM file is ~2.33MB. Now, from the Go install base, copy the wasm executor JS file over:

    cp $GOROOT/misc/wasm/wasm_exec.js wasm_exec.orig.js

Test it:

    node wasm_exec.orig.js hello-world.orig.wasm foo

Which will output:

    From hello-world.orig.wasm - Hello, foo!

Now bake it with two args, first being the program name `hello-world.baked.wasm` and the second being the first arg
`foo`:

    go-wasm-bake hello-world.orig.wasm hello-world.baked.wasm --arg hello-world.baked.wasm --arg foo

It will take a minute or so and give some output like:

    [INFO] Starting bake on hello-world.orig.wasm with args [hello-world.baked.wasm, foo]             
    [INFO] Baked WASM in 83752 ms, went from 2445309 to 2022376 bytes (17% smaller)                   
    [INFO] Went from 4205212 insns until main.main to 111                                             
    [INFO] Saving to hello-world.baked.wasm                                                           
    [INFO] JS required before go.run call in JS:                                                      
    -------------------                                                                               
    const putRef = (v) => {                                                                           
      const nanHead = 0x7FF80000;                                                                     
      if (v === undefined || v === null || v === true || v === false || typeof v === 'number') return;
      if (!this._refs.has(v)) {                                                                       
        const ref = this._values.length;                                                              
        this._values.push(v);                                                                         
        this._refs.set(v, ref);                                                                       
      }                                                                                               
    }                                                                                                 
    putRef(Reflect.get(this._values[5], 'Array'));                                                    
    putRef(Reflect.get(this._values[5], 'Object'));                                                   
    putRef(Reflect.get(this._values[5], 'Array'));                                                    
    putRef(Reflect.construct(this._values[8], []));                                                   
    putRef(Reflect.get(this._values[5], 'Go'));                                                       
    putRef(Reflect.get(this._values[11], '_makeCallbackHelper'));                                     
    putRef(Reflect.get(this._values[5], 'Go'));                                                       
    putRef(Reflect.get(this._values[11], '_makeEventCallbackHelper'));                                
    putRef(Reflect.get(this._values[5], 'Int8Array'));                                                
    putRef(Reflect.get(this._values[5], 'Int16Array'));                                               
    putRef(Reflect.get(this._values[5], 'Int32Array'));                                               
    putRef(Reflect.get(this._values[5], 'Uint8Array'));                                               
    putRef(Reflect.get(this._values[5], 'Uint16Array'));                                              
    putRef(Reflect.get(this._values[5], 'Uint32Array'));                                              
    putRef(Reflect.get(this._values[5], 'Float32Array'));                                             
    putRef(Reflect.get(this._values[5], 'Float64Array'));                                             
    putRef(Reflect.get(this._values[5], 'process'));                                                  
    putRef(Reflect.get(this._values[5], 'fs'));                                                       
    putRef(Reflect.get(this._values[23], 'constants'));                                               
    putRef(Reflect.get(this._values[24], 'O_WRONLY'));                                                
    putRef(Reflect.get(this._values[24], 'O_RDWR'));                                                  
    putRef(Reflect.get(this._values[24], 'O_CREAT'));                                                 
    putRef(Reflect.get(this._values[24], 'O_TRUNC'));                                                 
    putRef(Reflect.get(this._values[24], 'O_APPEND'));                                                
    putRef(Reflect.get(this._values[24], 'O_EXCL'));                                                  

Now `hello-world.baked.wasm` is ~1.92 MB. Some JS was given as a result of that command. It needs to be placed in the
WASM execution JS. First copy it over:

    cp wasm_exec.orig.js wasm_exec.baked.js

Then, just before the `while(true)` call, add the code above. This essentially makes the syscalls that the other WASM
does during initialization. Now, run it:

    node wasm_exec.baked.js hello-world.baked.js foo

Note, due to how baking works, the args must exactly match what was sent to `go-wasm-bake` or results are unpredictable
(see "Caveats" below). The output is:

    From hello-world.baked.wasm - Hello, foo!

## How It Works

I was surprised by how large the WASM binaries produced by Go were. I did some investigating and saw that there were
thousands of instructions on init of some packages that mostly just initialized memory. This is not specific to WASM,
and the biggest offender is the `unicode` package that instantiates a ton of vars on init. I mentioned it in an
[issue](https://github.com/golang/go/issues/26622) but I had were no real concrete suggestions for improvement. So I
wrote this experiment to see what easy savings I could get. This code does the following (in bullet points to keep it
straightforward):

* Builds an interpreter that keeps track of JS calls and breaks when it reaches `main.main`
* Runs the WASM code with the given args and environment variables expecting it to break at `main.main`
* Invokes `runtime.pause` which sets a few globals to values for resumption
* Hardcodes globals in new WASM to what they currently are except index 1, which is the resume point (i.e. `PC_B`), to 
  `runtime.main` to skip initialization
* Hardcodes the current set of memory data into the new WASM via WASM data segments. Creates a different data segment
  for each section of non-zero memory (allows up to 5 consecutive zeros before creating new section)
* Remove the code for all inits in new WASM that were already executed
* Clean up function types to fixate the locals count (doesn't help with size really, just a cleanup)
* Abstract a [common set of 9 instructions](https://github.com/golang/go/blob/12d27d8ea5a6980b741564e2229c281dedb547d2/src/cmd/internal/obj/wasm/wasmobj.go#L422-L440)
  into a function and change the thousands of places where it's inlined to call it
* Write new WASM file and output the needed JS calls

**Caveats**

This experiment has a few caveats and situations where it won't work:

* Requires the exact arguments, including the filename, given to the baker as will be given at runtime. This means
  callers cannot send situationally different args at runtime (but JS syscalls can be used for external data)
* Since it executes all inits, requires that they are mostly pure. Something in init that would do something different
  depending on time or make a JS syscall that could have different results would not work here obviously because the
  invocation results are saved
* May not support some cases of async code in init. I have not tested, but the Go WASM is run in a loop, so its possible
  it could not reach main before asking to be re-run
* Relies on internal knowledge of how Go emits WASM which means it is unstable beyond its specific targeted version
  (Go 1.11 as of this writing)
* The syscalls recorded are only for getting/memoizing JS values and creating new ones. Several other syscalls that
  happen across the JS boundary, such as setting properties, are not supported in init (but after `main.main`, sure)

**Why was it not written in Go?**

I needed an interpreter that I could step/stop when I wanted, customize, and inspect all sorts of information about the
stack and the instructions. As is common in Go projects, the WASM interpreters I have seen do not expose the internals I
need. Also I have a project I built and am familiar with, [Asmble](https://github.com/cretz/asmble), that does have a
steppable interpreter with internals exposed. So it was just easier for this proof of concept to use that. If any
concepts from this PoC deserve a more stable implementation, a Go implementation would be desired.

**Other improvements?**

This was just an experiment. There are many things that could be improved or added on:

* Put the JS syscalls right at the beginning of invocation in WASM instead of dumping out JS for the user (really easy)
* Remove all stack-only (i.e. temporary) memory that is not part of the current call stack. Not sure how much stack is
  overwritten normally. Could also remove GC'd memory based on GC mem tracking but I haven't investigated. Removing
  unused-but-set memory could potentially save a ton of space
* Remove dead code (i.e. unused functions) based on compile time lookup assuming no reflective access
* Remove unused data, e.g. all those reflection strings if they are unused and user doesn't mind a non-descriptive
  stack dump on panic
* Remove dead code and unused data based on profile-guided optimizations. Would require a more complete-built Go runtime
  so the interpreter could run to completion.
* Do this all in JS by just tracking syscalls, using the array buffer memory on break, and just injecting a single
  `unreachable` at the top of `main.main` (and exporting `runtime.pause` I guess).
* Make the solution more generic and applicable to all WASM code, ala https://prepack.io for WASM