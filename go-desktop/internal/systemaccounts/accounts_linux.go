package systemaccounts

import (
	"context"
	"errors"
	"net/mail"
	"sort"
	"strings"
	"time"

	"github.com/godbus/dbus/v5"
)

type managedObjects map[dbus.ObjectPath]map[string]map[string]dbus.Variant

func Emails() ([]string, error) {
	conn, err := dbus.ConnectSessionBus()
	if err != nil {
		return nil, errors.New("GNOME Online Accounts is unavailable")
	}
	defer conn.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
	defer cancel()
	object := conn.Object("org.gnome.OnlineAccounts", "/org/gnome/OnlineAccounts")
	call := object.CallWithContext(ctx, "org.freedesktop.DBus.ObjectManager.GetManagedObjects", 0)
	if call.Err != nil {
		return nil, errors.New("GNOME Online Accounts is unavailable")
	}
	var objects managedObjects
	if err := call.Store(&objects); err != nil {
		return nil, errors.New("could not read GNOME Online Accounts")
	}
	return emailsFromObjects(objects), nil
}

func emailsFromObjects(objects managedObjects) []string {
	seen := make(map[string]bool)
	result := make([]string, 0)
	for _, interfaces := range objects {
		account := interfaces["org.gnome.OnlineAccounts.Account"]
		for _, name := range []string{"PresentationIdentity", "Identity"} {
			identity, ok := account[name].Value().(string)
			if !ok {
				continue
			}
			identity = strings.TrimSpace(identity)
			parsed, err := mail.ParseAddress(identity)
			if err != nil || parsed.Address != identity || !strings.Contains(identity, "@") {
				continue
			}
			key := strings.ToLower(identity)
			if !seen[key] {
				seen[key] = true
				result = append(result, identity)
			}
			break
		}
	}
	sort.Slice(result, func(i, j int) bool { return strings.ToLower(result[i]) < strings.ToLower(result[j]) })
	return result
}
