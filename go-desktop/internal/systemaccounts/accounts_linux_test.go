package systemaccounts

import (
	"reflect"
	"strings"
	"testing"

	"github.com/godbus/dbus/v5"
)

func TestSystemEmailsUseOnlyGnomeAccountIdentities(t *testing.T) {
	objects := managedObjects{
		"/one":       {"org.gnome.OnlineAccounts.Account": {"PresentationIdentity": dbus.MakeVariant("First@example.test"), "Identity": dbus.MakeVariant("first@example.test")}},
		"/two":       {"org.gnome.OnlineAccounts.Account": {"PresentationIdentity": dbus.MakeVariant("not an email"), "Identity": dbus.MakeVariant("second@example.test")}},
		"/duplicate": {"org.gnome.OnlineAccounts.Account": {"PresentationIdentity": dbus.MakeVariant("first@example.test")}},
		"/other":     {"another.interface": {"PresentationIdentity": dbus.MakeVariant("not-a-system-account@example.test")}},
		"/invalid":   {"org.gnome.OnlineAccounts.Account": {"PresentationIdentity": dbus.MakeVariant("A User <name@example.test>")}},
	}
	got := emailsFromObjects(objects)
	for i := range got {
		got[i] = strings.ToLower(got[i])
	}
	want := []string{"first@example.test", "second@example.test"}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("got %#v, want %#v", got, want)
	}
}
