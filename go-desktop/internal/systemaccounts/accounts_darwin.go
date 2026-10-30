package systemaccounts

import "errors"

func Emails() ([]string, error) {
	return nil, errors.New("a system email picker is not available on this platform")
}
