package main

import (
	"fmt"
	"os"
)

func main() {
	fmt.Printf("From %v - Hello, %v!", os.Args[0], os.Args[1])
}
