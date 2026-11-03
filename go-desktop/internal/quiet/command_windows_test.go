package quiet

import (
	"testing"

	"golang.org/x/sys/windows"
)

func TestCommandHidesConsole(t *testing.T) {
	cmd := Command("route", "print")
	if cmd.SysProcAttr == nil || !cmd.SysProcAttr.HideWindow || cmd.SysProcAttr.CreationFlags&windows.CREATE_NO_WINDOW == 0 {
		t.Fatal("Windows child process can open a console")
	}
}
