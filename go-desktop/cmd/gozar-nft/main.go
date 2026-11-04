package main

import (
	"fmt"

	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/killswitch"
)

func main() {
	guard := killswitch.New("Gozar", nil)
	guard.Cgroup = "system.slice/gozar.service"
	fmt.Print(guard.LinuxRules())
}
