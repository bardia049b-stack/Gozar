package keychain

import (
	"errors"
	"os/exec"
	"runtime"
	"strings"

	"github.com/godbus/dbus/v5"
)

const serviceName = "org.gozar.Secrets"

type secretStruct struct {
	Session     dbus.ObjectPath
	Parameters  []byte
	Value       []byte
	ContentType string
}

var ErrUnavailable = errors.New("system keychain unavailable")

func Set(key, value string) error {
	switch runtime.GOOS {
	case "linux":
		return linuxSet(key, value)
	case "darwin":
		return darwinSet(key, value)
	default:
		return ErrUnavailable
	}
}

func Get(key string) (string, error) {
	switch runtime.GOOS {
	case "linux":
		return linuxGet(key)
	case "darwin":
		return darwinGet(key)
	default:
		return "", ErrUnavailable
	}
}

func Delete(key string) error {
	switch runtime.GOOS {
	case "linux":
		return linuxDelete(key)
	case "darwin":
		return darwinDelete(key)
	default:
		return ErrUnavailable
	}
}

func linuxSession() (*dbus.Conn, dbus.BusObject, error) {
	conn, err := dbus.SessionBus()
	if err != nil {
		return nil, nil, ErrUnavailable
	}
	service := conn.Object("org.freedesktop.secrets", "/org/freedesktop/secrets")
	return conn, service, nil
}

func linuxCollection(conn *dbus.Conn, service dbus.BusObject) (dbus.BusObject, error) {
	var collection dbus.ObjectPath
	if err := service.Call("org.freedesktop.Secret.Service.ReadAlias", 0, "default").Store(&collection); err != nil {
		return nil, ErrUnavailable
	}
	if collection == "/" {
		var prompt dbus.ObjectPath
		if err := service.Call("org.freedesktop.Secret.Service.CreateCollection", 0, map[string]any{
			"org.freedesktop.Secret.Collection.Label": "Gozar",
		}, "gozar").Store(&collection, &prompt); err != nil {
			return nil, ErrUnavailable
		}
	}
	return conn.Object("org.freedesktop.secrets", collection), nil
}

func linuxSet(key, value string) error {
	conn, service, err := linuxSession()
	if err != nil {
		return err
	}
	collection, err := linuxCollection(conn, service)
	if err != nil {
		return err
	}
	var session dbus.ObjectPath
	var output dbus.Variant
	if err := service.Call("org.freedesktop.Secret.Service.OpenSession", 0, "plain", dbus.Variant{}).Store(&output, &session); err != nil {
		return linuxSetFallback(key, value)
	}
	secret := secretStruct{Session: session, Parameters: []byte{}, Value: []byte(value), ContentType: "text/plain"}
	item := map[string]any{
		"org.freedesktop.Secret.Item.Label": serviceName + "." + key,
		"org.freedesktop.Secret.Item.Attributes": map[string]string{
			"service": serviceName,
			"account": key,
		},
	}
	var path dbus.ObjectPath
	var prompt dbus.ObjectPath
	if err := collection.Call("org.freedesktop.Secret.Collection.CreateItem", 0, item, secret, true).Store(&path, &prompt); err != nil {
		return linuxSetFallback(key, value)
	}
	return nil
}

func linuxSetFallback(key, value string) error {
	cmd := exec.Command("secret-tool", "store", "--label="+serviceName+"."+key, "service", serviceName, "account", key)
	cmd.Stdin = strings.NewReader(value)
	if err := cmd.Run(); err != nil {
		return ErrUnavailable
	}
	return nil
}

func linuxGet(key string) (string, error) {
	out, err := exec.Command("secret-tool", "lookup", "service", serviceName, "account", key).Output()
	if err != nil {
		return "", ErrUnavailable
	}
	return strings.TrimRight(string(out), "\r\n"), nil
}

func linuxDelete(key string) error {
	if err := exec.Command("secret-tool", "clear", "service", serviceName, "account", key).Run(); err != nil {
		return ErrUnavailable
	}
	return nil
}

func darwinSet(key, value string) error {
	if err := exec.Command("security", "add-generic-password", "-U", "-s", serviceName, "-a", key, "-w", value).Run(); err != nil {
		return ErrUnavailable
	}
	return nil
}

func darwinGet(key string) (string, error) {
	out, err := exec.Command("security", "find-generic-password", "-s", serviceName, "-a", key, "-w").Output()
	if err != nil {
		return "", ErrUnavailable
	}
	return strings.TrimRight(string(out), "\r\n"), nil
}

func darwinDelete(key string) error {
	if err := exec.Command("security", "delete-generic-password", "-s", serviceName, "-a", key).Run(); err != nil {
		return ErrUnavailable
	}
	return nil
}
