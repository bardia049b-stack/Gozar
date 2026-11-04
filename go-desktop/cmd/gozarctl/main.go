package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"strconv"
	"strings"

	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/rpc"
)

func main() {
	socket := flag.String("socket", "", "")
	flag.Parse()
	args := flag.Args()
	if len(args) == 0 {
		fmt.Fprintln(os.Stderr, "usage: gozarctl [--socket path] <state|connect|disconnect|servers|usage|settings|quit> [key=value...]")
		os.Exit(2)
	}
	request := rpc.Request{ID: 1, Method: args[0]}
	params := map[string]any{}
	for _, arg := range args[1:] {
		key, value, ok := strings.Cut(arg, "=")
		if !ok {
			fmt.Fprintln(os.Stderr, "invalid parameter: "+arg)
			os.Exit(2)
		}
		if number, err := strconv.Atoi(value); err == nil {
			params[key] = number
		} else if value == "true" || value == "false" {
			params[key] = value == "true"
		} else {
			params[key] = value
		}
	}
	if len(params) > 0 {
		request.Params, _ = json.Marshal(params)
	}
	response, err := rpc.RequestOnce(*socket, request)
	if err != nil {
		fmt.Fprintln(os.Stderr, err.Error())
		os.Exit(1)
	}
	encoder := json.NewEncoder(os.Stdout)
	encoder.SetIndent("", "  ")
	_ = encoder.Encode(response)
}
