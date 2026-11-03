package fxa

import (
	"encoding/hex"
	"encoding/json"
	"io"
	"net/http"
	"strings"
	"testing"
)

type fakeTransport func(*http.Request) (*http.Response, error)

func (f fakeTransport) RoundTrip(request *http.Request) (*http.Response, error) {
	return f(request)
}

func testResponse(code int, body string) *http.Response {
	return &http.Response{StatusCode: code, Header: make(http.Header), Body: io.NopCloser(strings.NewReader(body))}
}

func TestSignInCorrectsEmailCaseAndUsesSessionVerification(t *testing.T) {
	old := client
	defer func() { client = old }()
	count := 0
	client = &http.Client{Transport: fakeTransport(func(request *http.Request) (*http.Response, error) {
		count++
		body := map[string]string{}
		if err := json.NewDecoder(request.Body).Decode(&body); err != nil {
			t.Fatal(err)
		}
		switch count {
		case 1:
			if request.URL.Path != "/v1/account/login" || body["email"] != "user@example.org" {
				t.Fatal("incorrect first login request")
			}
			return testResponse(400, `{"errno":120,"email":"User@example.org"}`), nil
		case 2:
			if body["email"] != "User@example.org" || body["originalLoginEmail"] != "user@example.org" ||
				body["authPW"] != hex.EncodeToString(AuthPW("User@example.org", "fake-password")) {
				t.Fatal("incorrect retry for canonical email")
			}
			return testResponse(200, `{"uid":"example","sessionToken":"a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebf","verified":false,"verificationMethod":"email-2fa"}`), nil
		case 3:
			if request.URL.Path != "/v1/session/verify_code" || body["code"] != "123456" ||
				!strings.HasPrefix(request.Header.Get("Authorization"), "Hawk ") {
				t.Fatal("incorrect email verification request")
			}
			return testResponse(200, `{}`), nil
		case 4:
			if request.URL.Path != "/v1/session/verify/totp" || body["code"] != "654321" {
				t.Fatal("incorrect authenticator verification request")
			}
			return testResponse(200, `{"success":true}`), nil
		default:
			t.Fatal("unexpected request")
			return nil, nil
		}
	})}
	session, err := SignIn("user@example.org", "fake-password")
	if err != nil || session.Email != "User@example.org" || !session.NeedsOTP {
		t.Fatalf("unexpected login response: %v", err)
	}
	if err := VerifyCode(session.Token, "123456", session.VerificationMethod); err != nil {
		t.Fatal(err)
	}
	if err := VerifyCode(session.Token, "654321", "totp-2fa"); err != nil {
		t.Fatal(err)
	}
}
