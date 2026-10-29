package fxa

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

const (
	userAgent   = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:149.0) Gecko/20100101 Firefox/149.0"
	clientID    = "5882386c6d801776"
	scope       = "profile https://identity.mozilla.com/apps/vpn"
	authBase    = "https://api.accounts.firefox.com/v1"
	guardianURL = "https://vpn.mozilla.org"
	serversURL  = "https://firefox.settings.services.mozilla.com/v1/buckets/main/collections/vpn-serverlist/records"
)

var client = &http.Client{Timeout: 25 * time.Second}

type Reply struct {
	Code    int
	Body    []byte
	Headers http.Header
}

type Error struct {
	Message string
	Status  int
	Errno   int
}

func (e *Error) Error() string { return e.Message }

func call(method, url, body string, headers map[string]string) (*Reply, error) {
	var reader io.Reader
	if body != "" {
		reader = strings.NewReader(body)
	}
	request, err := http.NewRequest(method, url, reader)
	if err != nil {
		return nil, err
	}
	request.Header.Set("User-Agent", userAgent)
	request.Header.Set("Accept", "application/json")
	if body != "" {
		request.Header.Set("Content-Type", "application/json")
	}
	for name, value := range headers {
		request.Header.Set(name, value)
	}
	response, err := client.Do(request)
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()
	payload, err := io.ReadAll(io.LimitReader(response.Body, 1<<22))
	if err != nil {
		return nil, err
	}
	return &Reply{Code: response.StatusCode, Body: payload, Headers: response.Header}, nil
}

func failure(reply *Reply) error {
	var data struct {
		Message string `json:"message"`
		Code    string `json:"code"`
		Errno   int    `json:"errno"`
	}
	json.Unmarshal(reply.Body, &data)
	message := data.Message
	if message == "" {
		message = data.Code
	}
	if message == "" {
		message = fmt.Sprintf("HTTP %d", reply.Code)
	}
	if reply.Code == 406 {
		message = "Mozilla's anti-bot check needs another try"
	}
	return &Error{Message: message, Status: reply.Code, Errno: data.Errno}
}

func decode(reply *Reply, target any) error {
	if reply.Code < 200 || reply.Code > 299 {
		return failure(reply)
	}
	if err := json.Unmarshal(reply.Body, target); err != nil {
		return errors.New("unexpected response from Mozilla")
	}
	return nil
}

type Session struct {
	UID      string
	Token    string
	NeedsOTP bool
	ByLink   bool
}

type Tokens struct {
	Access    string `json:"access_token"`
	Refresh   string `json:"refresh_token"`
	ExpiresIn int    `json:"expires_in"`
}

type Pass struct {
	Token     string
	Limit     int64
	Remaining int64
	Reset     string
}

type signInReply struct {
	UID                string `json:"uid"`
	SessionToken       string `json:"sessionToken"`
	Verified           bool   `json:"verified"`
	VerificationMethod string `json:"verificationMethod"`
}

func SignIn(email, password string) (*Session, error) {
	authPw := fmt.Sprintf("%x", AuthPW(email, password))
	body, _ := json.Marshal(map[string]any{
		"email":              strings.TrimSpace(email),
		"authPW":             authPw,
		"verificationMethod": "email-2fa",
	})
	reply, err := call("POST", authBase+"/account/login", string(body), nil)
	if err != nil {
		return nil, err
	}
	if reply.Code == 400 && replyCode(reply.Body) == 107 {
		body, _ := json.Marshal(map[string]any{
			"email":  strings.TrimSpace(email),
			"authPW": authPw,
		})
		if reply, err = call("POST", authBase+"/account/login", string(body), nil); err != nil {
			return nil, err
		}
	}
	return readSession(reply)
}

func replyCode(body []byte) int {
	var data struct {
		Errno int `json:"errno"`
	}
	json.Unmarshal(body, &data)
	return data.Errno
}

func readSession(reply *Reply) (*Session, error) {
	var data signInReply
	if err := decode(reply, &data); err != nil {
		return nil, err
	}
	return &Session{
		UID:      data.UID,
		Token:    data.SessionToken,
		NeedsOTP: !data.Verified,
		ByLink:   data.VerificationMethod == "email",
	}, nil
}

func Register(email, password string) (*Session, error) {
	body, _ := json.Marshal(map[string]any{
		"email":              strings.TrimSpace(email),
		"authPW":             fmt.Sprintf("%x", AuthPW(email, password)),
		"service":            "sync",
		"verificationMethod": "email-otp",
	})
	reply, err := call("POST", authBase+"/account/create", string(body), nil)
	if err != nil {
		return nil, err
	}
	return readSession(reply)
}

func VerifyCode(sessionToken, uid, code string) error {
	signed, _ := json.Marshal(map[string]string{"code": code})
	url := authBase + "/recovery_email/verify_code"
	header := HawkHeader("POST", url, sessionToken, string(signed))
	reply, err := call("POST", url, string(signed), map[string]string{"Authorization": header})
	if err == nil && reply.Code >= 200 && reply.Code <= 299 {
		return nil
	}
	plain, _ := json.Marshal(map[string]string{"uid": uid, "code": code})
	reply, err = call("POST", authBase+"/recovery_email/verify_code", string(plain), nil)
	if err == nil && reply.Code >= 200 && reply.Code <= 299 {
		return nil
	}
	return errors.New("The code was not accepted")
}

func SessionTokens(sessionToken string) (*Tokens, error) {
	body, _ := json.Marshal(map[string]string{
		"client_id":    clientID,
		"grant_type":   "fxa-credentials",
		"scope":        scope,
		"access_type":  "offline",
	})
	url := authBase + "/oauth/token"
	header := HawkHeader("POST", url, sessionToken, string(body))
	reply, err := call("POST", url, string(body), map[string]string{"Authorization": header})
	if err != nil {
		return nil, err
	}
	var out Tokens
	if err := decode(reply, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

func Refresh(refreshToken string) (*Tokens, error) {
	body, _ := json.Marshal(map[string]string{
		"client_id":     clientID,
		"grant_type":    "refresh_token",
		"refresh_token": refreshToken,
		"scope":         scope,
	})
	reply, err := call("POST", authBase+"/oauth/token", string(body), nil)
	if err != nil {
		return nil, err
	}
	var out Tokens
	if err := decode(reply, &out); err != nil {
		return nil, err
	}
	if out.Refresh == "" {
		out.Refresh = refreshToken
	}
	return &out, nil
}

func quota(reply *Reply) *Pass {
	if strings.EqualFold(strings.TrimSpace(reply.Headers.Get("X-Quota-Unlimited")), "true") {
		return &Pass{Limit: -1, Remaining: -1}
	}
	limit, err := parseInt(reply.Headers.Get("X-Quota-Limit"))
	if err != nil {
		return nil
	}
	remaining, err := parseInt(reply.Headers.Get("X-Quota-Remaining"))
	if err != nil {
		return nil
	}
	return &Pass{Limit: limit, Remaining: remaining, Reset: reply.Headers.Get("X-Quota-Reset")}
}

func parseInt(text string) (int64, error) {
	var value int64
	_, err := fmt.Sscan(strings.TrimSpace(text), &value)
	return value, err
}

func PassToken(access string) (*Pass, error) {
	reply, err := call("GET", guardianURL+"/api/v1/fpn/token", "", map[string]string{
		"Authorization": "Bearer " + access,
	})
	if err != nil {
		return nil, err
	}
	if reply.Code == 403 {
		call("POST", guardianURL+"/api/v1/fpn/activate", "{}", map[string]string{
			"Authorization": "Bearer " + access,
		})
		reply, err = call("GET", guardianURL+"/api/v1/fpn/token", "", map[string]string{
			"Authorization": "Bearer " + access,
		})
		if err != nil {
			return nil, err
		}
	}
	if reply.Code != 200 {
		return nil, failure(reply)
	}
	var data struct {
		Token string `json:"token"`
	}
	json.Unmarshal(reply.Body, &data)
	if data.Token == "" {
		return nil, errors.New("Mozilla did not return a proxy pass")
	}
	pass := quota(reply)
	if pass == nil {
		pass = &Pass{}
	}
	pass.Token = data.Token
	return pass, nil
}

func Usage(access string) (*Pass, error) {
	reply, err := call("HEAD", guardianURL+"/api/v1/fpn/token", "", map[string]string{
		"Authorization": "Bearer " + access,
	})
	if err != nil || reply.Code != 200 {
		return nil, errors.New("usage is not available")
	}
	return quota(reply), nil
}

type Location struct {
	Code        string `json:"code"`
	Country     string `json:"country"`
	City        string `json:"city"`
	Host        string `json:"host"`
	Port        int    `json:"port"`
	Recommended bool   `json:"recommended"`
}

type serverCity struct {
	Name    string `json:"name"`
	Servers []struct {
		Hostname    string `json:"hostname"`
		Port        int    `json:"port"`
		Quarantined bool   `json:"quarantined"`
		Protocols   []struct {
			Scheme string `json:"scheme"`
			Host   string `json:"host"`
			Port   int    `json:"port"`
		} `json:"protocols"`
	} `json:"servers"`
}

func Servers() ([]Location, error) {
	reply, err := call("GET", serversURL, "", nil)
	if err != nil {
		return nil, err
	}
	if reply.Code != 200 {
		return nil, failure(reply)
	}
	var data struct {
		Data []struct {
			Code   string       `json:"code"`
			Name   string       `json:"name"`
			Cities []serverCity `json:"cities"`
		} `json:"data"`
	}
	if err := json.Unmarshal(reply.Body, &data); err != nil {
		return nil, err
	}
	var out []Location
	for _, country := range data.Data {
		if country.Code == "" {
			continue
		}
		for _, city := range country.Cities {
			host, port := pickServer(city)
			if host == "" {
				continue
			}
			out = append(out, Location{
				Code:        country.Code,
				Country:     country.Name,
				City:        city.Name,
				Host:        host,
				Port:        port,
				Recommended: country.Code == "REC",
			})
		}
	}
	return out, nil
}

func pickServer(city serverCity) (string, int) {
	for _, server := range city.Servers {
		if server.Quarantined {
			continue
		}
		for _, proto := range server.Protocols {
			if proto.Scheme != "" && !strings.EqualFold(proto.Scheme, "https") {
				continue
			}
			host := proto.Host
			if host == "" {
				host = server.Hostname
			}
			port := proto.Port
			if port == 0 {
				port = server.Port
			}
			if port == 0 {
				port = 443
			}
			if host != "" {
				return host, port
			}
		}
		if server.Hostname != "" {
			port := server.Port
			if port == 0 {
				port = 443
			}
			return server.Hostname, port
		}
	}
	return "", 0
}

func SpliceJSON(raw []byte) string {
	return string(bytes.TrimSpace(raw))
}
