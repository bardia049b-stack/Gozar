package main

import (
	"flag"
	"fmt"
	"os"
	"os/signal"
	"strings"
	"syscall"

	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/killswitch"
)

func main() {
	action := flag.String("action", "rules", "apply, hold, remove, reap or rules")
	iface := flag.String("iface", "Gozar", "tunnel interface name")
	keep := flag.String("keep", "", "comma separated addresses allowed outside the tunnel")
	flag.Parse()

	var addresses []string
	for _, item := range strings.Split(*keep, ",") {
		if trimmed := strings.TrimSpace(item); trimmed != "" {
			addresses = append(addresses, trimmed)
		}
	}
	guard := killswitch.New(*iface, addresses)

	switch *action {
	case "rules":
		fmt.Print(guard.LinuxRules())
	case "reap":
		killswitch.Reap()
		fmt.Println("reaped")
	case "apply", "hold":
		if err := guard.Apply(); err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(1)
		}
		fmt.Println("applied")
		if *action == "hold" {
			wait := make(chan os.Signal, 1)
			signal.Notify(wait, syscall.SIGTERM, syscall.SIGINT)
			<-wait
			if err := guard.Remove(); err != nil {
				fmt.Fprintln(os.Stderr, err)
				os.Exit(1)
			}
			fmt.Println("removed")
		}
	case "remove":
		killswitch.Reap()
		fmt.Println("removed")
	default:
		fmt.Fprintln(os.Stderr, "unknown action")
		os.Exit(2)
	}
}
