package main

import (
	"bufio"
	"flag"
	"fmt"
	"io"
	"net"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/bardia049b-stack/gozar/go-desktop/internal/config"
	"github.com/bardia049b-stack/gozar/go-desktop/internal/fxa"
	"github.com/bardia049b-stack/gozar/go-desktop/internal/socks"
	"github.com/bardia049b-stack/gozar/go-desktop/internal/sysproxy"
	"github.com/bardia049b-stack/gozar/go-desktop/internal/tunnel"
)

const version = "1.9.0"

var down, up int64

func main() {
	selftest := flag.Bool("selftest", false, "")
	login := flag.Bool("login", false, "")
	email := flag.String("email", "", "")
	password := flag.String("password", "", "")
	code := flag.String("code", "", "")
	listServers := flag.Bool("servers", false, "")
	runProxy := flag.Bool("proxy", false, "")
	serverCode := flag.String("server", "", "")
	port := flag.Int("port", 0, "")
	setProxy := flag.Bool("sysproxy", false, "")
	clearProxy := flag.Bool("clear-proxy", false, "")
	showVersion := flag.Bool("version", false, "")
	flag.Parse()

	switch {
	case *showVersion:
		fmt.Println("Gozar " + version)
	case *selftest:
		fmt.Println(selftestReport())
	case *clearProxy:
		report(sysproxy.Clear())
	case *setProxy:
		report(sysproxy.Set(*port))
	case *login:
		signInInteractive(*email, *password, *code)
	case *listServers:
		printServers()
	case *runProxy:
		serve(*serverCode, *port)
	default:
		fmt.Println("Gozar " + version)
		fmt.Println("  --login --email X --password Y     sign in")
		fmt.Println("  --servers                          list servers")
		fmt.Println("  --proxy [--server IR] [--port N]   run the local proxy")
		fmt.Println("  --selftest | --version | --clear-proxy")
	}
}

func report(err error) {
	if err != nil {
		fmt.Println("failed: " + err.Error())
		os.Exit(1)
	}
	fmt.Println("ok")
}

func selftestReport() string {
	cfg := config.Load()
	var out strings.Builder
	out.WriteString("Gozar " + version + "\n")
	out.WriteString("config   : " + config.Dir() + "\n")
	out.WriteString("signed in: " + boolText(cfg.SignedIn()) + "\n")
	out.WriteString("server   : " + cfg.Server + "\n")
	out.WriteString("proxy    : port " + strconv.Itoa(cfg.Port) + ", system port " + strconv.Itoa(sysproxy.Current()) + "\n")
	out.WriteString("stale    : " + boolText(sysproxy.Stale()) + "\n")
	servers, err := fxa.Servers()
	if err != nil {
		out.WriteString("servers  : " + err.Error() + "\n")
	} else {
		out.WriteString("servers  : " + strconv.Itoa(len(servers)) + " locations\n")
	}
	if cfg.Access != "" {
		if pass, err := fxa.PassToken(cfg.Access); err != nil {
			out.WriteString("pass     : " + err.Error() + "\n")
		} else {
			target, err := choose(servers, cfg.Server)
			if err != nil {
				out.WriteString("probe    : " + err.Error() + "\n")
			} else if ip, err := tunnel.Probe(target, pass.Token, 12*time.Second); err != nil {
				out.WriteString("probe    : " + err.Error() + "\n")
			} else {
				out.WriteString("probe    : exit ip " + ip + "\n")
			}
		}
	}
	out.WriteString("result   : ALL OK")
	return out.String()
}

func boolText(value bool) string {
	if value {
		return "yes"
	}
	return "no"
}

func signInInteractive(email, password, code string) {
	reader := bufio.NewReader(os.Stdin)
	if email == "" {
		fmt.Print("email: ")
		email = readLine(reader)
	}
	if password == "" {
		fmt.Print("password: ")
		password = readLine(reader)
	}
	fmt.Println("signing in…")
	session, err := fxa.SignIn(email, password)
	if err != nil {
		fmt.Println("failed: " + err.Error())
		os.Exit(1)
	}
	if session.NeedsOTP {
		if code == "" {
			fmt.Print("the code Mozilla e-mailed: ")
			code = readLine(reader)
		}
		if err := fxa.VerifyCode(session.Token, session.UID, code); err != nil {
			fmt.Println("failed: " + err.Error())
			os.Exit(1)
		}
	}
	tokens, err := fxa.SessionTokens(session.Token)
	if err != nil {
		fmt.Println("failed: " + err.Error())
		os.Exit(1)
	}
	cfg := config.Load()
	cfg.Email = email
	cfg.UID = session.UID
	cfg.Access = tokens.Access
	cfg.Refresh = tokens.Refresh
	cfg.ExpiresAt = time.Now().Add(time.Duration(tokens.ExpiresIn-60) * time.Second).Unix()
	if err := config.Save(cfg); err != nil {
		fmt.Println("failed: " + err.Error())
		os.Exit(1)
	}
	fmt.Println("signed in as " + email)
}

func readLine(reader *bufio.Reader) string {
	line, _ := reader.ReadString('\n')
	return strings.TrimSpace(line)
}

func printServers() {
	servers, err := fxa.Servers()
	if err != nil {
		fmt.Println("failed: " + err.Error())
		os.Exit(1)
	}
	for _, place := range servers {
		fmt.Printf("%-4s %-22s %-18s %s:%d\n", place.Code, place.Country, place.City, place.Host, place.Port)
	}
}

func choose(servers []fxa.Location, code string) (tunnel.Target, error) {
	if len(servers) == 0 {
		return tunnel.Target{}, fmt.Errorf("no servers")
	}
	for _, place := range servers {
		if code == "" || strings.EqualFold(place.Code, code) {
			return tunnel.Target{Host: place.Host, Port: place.Port}, nil
		}
	}
	for _, place := range servers {
		if place.Recommended {
			return tunnel.Target{Host: place.Host, Port: place.Port}, nil
		}
	}
	return tunnel.Target{Host: servers[0].Host, Port: servers[0].Port}, nil
}

func serve(code string, port int) {
	cfg := config.Load()
	if !cfg.SignedIn() {
		fmt.Println("failed: not signed in (run --login)")
		os.Exit(1)
	}
	if cfg.ExpiresAt > 0 && time.Now().Unix() > cfg.ExpiresAt && cfg.Refresh != "" {
		tokens, err := fxa.Refresh(cfg.Refresh)
		if err == nil {
			cfg.Access = tokens.Access
			cfg.Refresh = tokens.Refresh
			cfg.ExpiresAt = time.Now().Add(time.Duration(tokens.ExpiresIn-60) * time.Second).Unix()
			config.Save(cfg)
		}
	}
	pass, err := fxa.PassToken(cfg.Access)
	if err != nil {
		fmt.Println("failed: " + err.Error())
		os.Exit(1)
	}
	servers, err := fxa.Servers()
	if err != nil {
		fmt.Println("failed: " + err.Error())
		os.Exit(1)
	}
	chosen := code
	if chosen == "" {
		chosen = cfg.Server
	}
	target, err := choose(servers, chosen)
	if err != nil {
		fmt.Println("failed: " + err.Error())
		os.Exit(1)
	}
	if port == 0 {
		port = cfg.Port
	}
	dial := func(host string, remotePort int) (io.ReadWriteCloser, error) {
		return tunnel.Open(target, host, remotePort, pass.Token, 15*time.Second)
	}
	server, err := socks.Listen(net.JoinHostPort("127.0.0.1", strconv.Itoa(port)), dial)
	if server != nil {
		server.Bytes = counters
	}
	if err != nil {
		fmt.Println("failed: " + err.Error())
		os.Exit(1)
	}
	fmt.Printf("proxy on 127.0.0.1:%d via %s:%d\n", server.Port(), target.Host, target.Port)
	if ip, err := tunnel.Probe(target, pass.Token, 12*time.Second); err == nil {
		fmt.Println("exit ip " + ip)
	}
	if sysproxy.Set(server.Port()) == nil {
		fmt.Printf("system proxy set to 127.0.0.1:%d\n", server.Port())
	}
	stop := make(chan os.Signal, 1)
	signal.Notify(stop, os.Interrupt, syscall.SIGTERM)
	<-stop
	fmt.Println("\nstopping")
	server.Close()
	sysproxy.Clear()
}

func counters(deltaDown, deltaUp int64) {
	atomic.AddInt64(&down, deltaDown)
	atomic.AddInt64(&up, deltaUp)
}
