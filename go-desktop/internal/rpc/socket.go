package rpc

import (
	"bufio"
	"encoding/json"
	"errors"
	"net"
	"os"
	"path/filepath"
	"runtime"

	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/app"
)

func DefaultSocketPath() string {
	if runtime.GOOS == "windows" {
		return `\\.\pipe\gozar-control`
	}
	dir, err := os.UserConfigDir()
	if err != nil {
		dir = os.TempDir()
	}
	return filepath.Join(dir, "Gozar", "control.sock")
}

func Listen(current *app.App, path string) (net.Listener, error) {
	if path == "" {
		path = DefaultSocketPath()
	}
	if runtime.GOOS != "windows" {
		if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
			return nil, err
		}
		_ = os.Remove(path)
		listener, err := net.Listen("unix", path)
		if err != nil {
			return nil, err
		}
		_ = os.Chmod(path, 0o600)
		return listener, nil
	}
	return listenWindows(path)
}

func Serve(current *app.App, listener net.Listener) {
	for {
		conn, err := listener.Accept()
		if err != nil {
			return
		}
		go serveConn(current, conn)
	}
}

func serveConn(current *app.App, conn net.Conn) {
	defer conn.Close()
	server := &Server{app: current, out: conn}
	reader := bufio.NewReaderSize(conn, 1<<20)
	for {
		line, err := reader.ReadBytes('\n')
		if len(line) > 0 {
			var request Request
			if json.Unmarshal(line, &request) == nil {
				if err := server.emit(server.handle(request)); err != nil || request.Method == "quit" {
					return
				}
			}
		}
		if err != nil {
			return
		}
	}
}

func RequestOnce(path string, request Request) (Response, error) {
	if path == "" {
		path = DefaultSocketPath()
	}
	conn, err := dialSocket(path)
	if err != nil {
		return Response{}, err
	}
	defer conn.Close()
	if err := json.NewEncoder(conn).Encode(request); err != nil {
		return Response{}, err
	}
	var response Response
	if err := json.NewDecoder(bufio.NewReader(conn)).Decode(&response); err != nil {
		return Response{}, err
	}
	if response.Error != "" {
		return response, errors.New(response.Error)
	}
	return response, nil
}
