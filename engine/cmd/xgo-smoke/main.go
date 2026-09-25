package main

import (
	"fmt"
	"os"

	"github.com/subhra74/xdm/engine/domain/identity"
	"github.com/subhra74/xdm/engine/foundation/idgen/idgentest"
)

func main() {
	generated, err := identity.NewOperationID(idgentest.NewCounter(1))
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	fmt.Printf("xgo-foundation %s\n", generated)
}
