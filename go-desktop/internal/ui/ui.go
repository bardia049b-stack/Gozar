package ui

import (
	_ "embed"
	"encoding/json"
	"net"
	"net/http"
	"os/exec"
	"runtime"
	"strconv"

	"github.com/bardia049b-stack/gozar/go-desktop/internal/app"
)

//go:embed index.html
var page []byte

type server struct {
	app *app.App
}

func Run(current *app.App) (string, error) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return "", err
	}
	handler := &server{app: current}
	mux := http.NewServeMux()
	mux.HandleFunc("/", handler.index)
	mux.HandleFunc("/api/state", handler.state)
	mux.HandleFunc("/api/servers", handler.servers)
	mux.HandleFunc("/api/login", handler.login)
	mux.HandleFunc("/api/logout", handler.logout)
	mux.HandleFunc("/api/connect", handler.connect)
	mux.HandleFunc("/api/disconnect", handler.disconnect)
	mux.HandleFunc("/api/server", handler.setServer)
	mux.HandleFunc("/api/mode", handler.setMode)
	mux.HandleFunc("/api/usage", handler.usage)
	go http.Serve(listener, mux)
	address := "http://" + listener.Addr().String()
	return address, nil
}

func Open(address string) {
	switch runtime.GOOS {
	case "windows":
		exec.Command("rundll32", "url.dll,FileProtocolHandler", address).Start()
	case "darwin":
		exec.Command("open", address).Start()
	default:
		exec.Command("xdg-open", address).Start()
	}
}

func (s *server) index(writer http.ResponseWriter, request *http.Request) {
	writer.Header().Set("Content-Type", "text/html; charset=utf-8")
	writer.Write(page)
}

func answer(writer http.ResponseWriter, value any) {
	writer.Header().Set("Content-Type", "application/json; charset=utf-8")
	json.NewEncoder(writer).Encode(value)
}

func (s *server) state(writer http.ResponseWriter, request *http.Request) {
	state := s.app.State()
	down, up := s.app.Rates()
	answer(writer, map[string]any{"state": state, "rate_down": down, "rate_up": up})
}

func (s *server) servers(writer http.ResponseWriter, request *http.Request) {
	servers, err := s.app.LoadServers()
	if err != nil {
		answer(writer, map[string]any{"error": err.Error()})
		return
	}
	answer(writer, map[string]any{"servers": servers})
}

func (s *server) login(writer http.ResponseWriter, request *http.Request) {
	var body struct {
		Email    string `json:"email"`
		Password string `json:"password"`
		Code     string `json:"code"`
	}
	if err := json.NewDecoder(request.Body).Decode(&body); err != nil {
		answer(writer, map[string]any{"error": "bad request"})
		return
	}
	if err := s.app.SignIn(body.Email, body.Password, body.Code); err != nil {
		answer(writer, map[string]any{"error": err.Error(), "needs_code": app.NeedsCode(err)})
		return
	}
	answer(writer, map[string]any{"ok": true})
}

func (s *server) logout(writer http.ResponseWriter, request *http.Request) {
	s.app.SignOut()
	answer(writer, map[string]any{"ok": true})
}

func (s *server) connect(writer http.ResponseWriter, request *http.Request) {
	if err := s.app.Connect(); err != nil {
		answer(writer, map[string]any{"error": err.Error()})
		return
	}
	answer(writer, map[string]any{"ok": true})
}

func (s *server) disconnect(writer http.ResponseWriter, request *http.Request) {
	s.app.Disconnect()
	answer(writer, map[string]any{"ok": true})
}

func (s *server) setServer(writer http.ResponseWriter, request *http.Request) {
	code := request.URL.Query().Get("code")
	s.app.SetServer(code)
	answer(writer, map[string]any{"ok": true})
}

func (s *server) setMode(writer http.ResponseWriter, request *http.Request) {
	mode := request.URL.Query().Get("mode")
	s.app.SetMode(mode == "tun")
	answer(writer, map[string]any{"ok": true})
}

func (s *server) usage(writer http.ResponseWriter, request *http.Request) {
	s.app.RefreshUsage()
	state := s.app.State()
	answer(writer, map[string]any{"limit": state.Limit, "remaining": state.Remaining, "reset": state.Reset})
}

var _ = strconv.Itoa
