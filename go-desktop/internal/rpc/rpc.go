package rpc

import (
	"bufio"
	"encoding/json"
	"errors"
	"io"
	"os"
	"sync"

	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/app"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/fxa"
	"github.com/bardia049b-stack/Gozar1/go-desktop/internal/systemaccounts"
)

type Request struct {
	ID     int             `json:"id"`
	Method string          `json:"method"`
	Params json.RawMessage `json:"params,omitempty"`
}

type Response struct {
	ID    int        `json:"id"`
	Error string     `json:"error,omitempty"`
	State *app.State `json:"state,omitempty"`
	Reply any        `json:"reply,omitempty"`
}

type Server struct {
	app   *app.App
	write sync.Mutex
	out   io.Writer
}

func Run(current *app.App) {
	ignorePipe()
	server := &Server{app: current, out: os.Stdout}
	reader := bufio.NewReaderSize(os.Stdin, 1<<20)
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

func (s *Server) handle(request Request) Response {
	response := Response{ID: request.ID}
	switch request.Method {
	case "state":
	case "servers":
		servers, err := s.app.LoadServers()
		if err != nil {
			response.Error = err.Error()
		} else {
			response.Reply = servers
		}
	case "login", "register":
		var body struct {
			Email    string `json:"email"`
			Password string `json:"password"`
			Code     string `json:"code"`
			Captcha  string `json:"captcha"`
		}
		json.Unmarshal(request.Params, &body)
		if err := s.app.Authenticate(body.Email, body.Password, body.Code, body.Captcha, request.Method == "register"); err != nil {
			response.Error = err.Error()
			var challenge *fxa.ChallengeRequired
			switch {
			case app.NeedsCode(err):
				response.Reply = map[string]any{"needs_code": true}
			case errors.As(err, &challenge):
				response.Reply = map[string]any{"captcha_image": challenge.Image}
			}
		}
	case "system_accounts":
		accounts, err := systemaccounts.Emails()
		if err != nil {
			response.Error = err.Error()
		} else {
			response.Reply = map[string]any{"emails": accounts}
		}
	case "cancel_auth":
		s.app.CancelAuthentication()
	case "logout":
		if err := s.app.SignOut(); err != nil {
			response.Error = err.Error()
		}
	case "switch_account":
		var body struct {
			ID string `json:"id"`
		}
		if err := json.Unmarshal(request.Params, &body); err != nil || body.ID == "" {
			response.Error = "invalid account"
		} else if err := s.app.SwitchAccount(body.ID); err != nil {
			response.Error = err.Error()
		}
	case "usage":
		if err := s.app.RefreshUsage(); err != nil {
			response.Error = err.Error()
		}
	case "connect":
		if err := s.app.Connect(); err != nil {
			response.Error = err.Error()
		}
	case "disconnect":
		s.app.Disconnect()
	case "server":
		var body struct {
			Host string `json:"host"`
			Code string `json:"code"`
		}
		json.Unmarshal(request.Params, &body)
		key := body.Host
		if key == "" {
			key = body.Code
		}
		if err := s.app.SetServer(key); err != nil {
			response.Error = err.Error()
		}
	case "language":
		var body struct {
			Language string `json:"language"`
		}
		json.Unmarshal(request.Params, &body)
		if err := s.app.SetLanguage(body.Language); err != nil {
			response.Error = err.Error()
		}
	case "theme":
		var body struct {
			Theme string `json:"theme"`
		}
		if err := json.Unmarshal(request.Params, &body); err != nil {
			response.Error = "invalid theme"
		} else if err := s.app.SetTheme(body.Theme); err != nil {
			response.Error = err.Error()
		}
	case "recents":
		var body struct {
			Enabled *bool `json:"enabled"`
		}
		if err := json.Unmarshal(request.Params, &body); err != nil || body.Enabled == nil {
			response.Error = "invalid recent-server setting"
		} else if err := s.app.SetRecents(*body.Enabled); err != nil {
			response.Error = err.Error()
		}
	case "chain":
		var body struct {
			Hops []string `json:"hops"`
		}
		if err := json.Unmarshal(request.Params, &body); err != nil || body.Hops == nil {
			response.Error = "invalid proxy chain"
		} else if err := s.app.SetChain(body.Hops); err != nil {
			response.Error = err.Error()
		}
	case "mode":
		var body struct {
			Tunnel bool `json:"tunnel"`
		}
		json.Unmarshal(request.Params, &body)
		if err := s.app.SetMode(body.Tunnel); err != nil {
			response.Error = err.Error()
		}
	case "quit":
		s.app.Close()
		response.State = statePointer(s.app.State())
	default:
		response.Error = "unknown method"
	}
	if response.State == nil {
		response.State = statePointer(s.app.State())
	}
	return response
}

func (s *Server) emit(response Response) error {
	encoder := json.NewEncoder(s.out)
	s.write.Lock()
	defer s.write.Unlock()
	return encoder.Encode(response)
}

func statePointer(value app.State) *app.State { return &value }
