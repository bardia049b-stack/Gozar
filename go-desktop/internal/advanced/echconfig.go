package advanced

import (
	"encoding/binary"
	"errors"
)

// ECHConfig is the subset of an ECHConfig (draft-ietf-tls-esni) Gozar needs:
// enough to validate a published config list and to show the cover name.
type ECHConfig struct {
	Raw        []byte
	ConfigID   uint8
	KEMID      uint16
	PublicKey  []byte
	PublicName string
}

const echVersion = 0xfe0d

// ParseECHConfigList validates the wire format Go's crypto/tls expects.
func ParseECHConfigList(list []byte) ([]ECHConfig, error) {
	if len(list) < 2 {
		return nil, errors.New("the ECH config list is too short")
	}
	if int(binary.BigEndian.Uint16(list)) != len(list)-2 {
		return nil, errors.New("the ECH config list length does not match")
	}
	rest := list[2:]
	var configs []ECHConfig
	for len(rest) > 0 {
		if len(rest) < 4 {
			return nil, errors.New("truncated ECHConfig")
		}
		version := binary.BigEndian.Uint16(rest)
		size := int(binary.BigEndian.Uint16(rest[2:]))
		if len(rest) < size+4 {
			return nil, errors.New("ECHConfig length exceeds the list")
		}
		raw := rest[:size+4]
		body := rest[4 : size+4]
		rest = rest[size+4:]
		if version != echVersion {
			continue // unknown versions are skipped, exactly like crypto/tls
		}
		config, err := parseECHConfigBody(raw, body)
		if err != nil {
			return nil, err
		}
		configs = append(configs, config)
	}
	if len(configs) == 0 {
		return nil, errors.New("the ECH config list has no usable config")
	}
	return configs, nil
}

func parseECHConfigBody(raw, body []byte) (ECHConfig, error) {
	config := ECHConfig{Raw: append([]byte(nil), raw...)}
	if len(body) < 3 {
		return config, errors.New("ECHConfig is too short")
	}
	config.ConfigID = body[0]
	config.KEMID = binary.BigEndian.Uint16(body[1:3])
	body = body[3:]
	if len(body) < 2 {
		return config, errors.New("ECHConfig public key is missing")
	}
	keyLen := int(binary.BigEndian.Uint16(body))
	body = body[2:]
	if keyLen == 0 || len(body) < keyLen {
		return config, errors.New("ECHConfig public key length is invalid")
	}
	config.PublicKey = append([]byte(nil), body[:keyLen]...)
	body = body[keyLen:]
	if len(body) < 2 {
		return config, errors.New("ECHConfig cipher suites are missing")
	}
	suitesLen := int(binary.BigEndian.Uint16(body))
	body = body[2:]
	if suitesLen == 0 || suitesLen%4 != 0 || len(body) < suitesLen {
		return config, errors.New("ECHConfig cipher suites are invalid")
	}
	body = body[suitesLen:]
	if len(body) < 2 {
		return config, errors.New("ECHConfig maximum name length is missing")
	}
	body = body[1:]
	nameLen := int(body[0])
	body = body[1:]
	if nameLen == 0 || len(body) < nameLen {
		return config, errors.New("ECHConfig public name is invalid")
	}
	config.PublicName = string(body[:nameLen])
	body = body[nameLen:]
	if len(body) < 2 {
		return config, errors.New("ECHConfig extensions are missing")
	}
	extLen := int(binary.BigEndian.Uint16(body))
	body = body[2:]
	if len(body) < extLen {
		return config, errors.New("ECHConfig extensions are truncated")
	}
	return config, nil
}

// ECHConfigSpec describes a config to publish (used by tests and tooling).
type ECHConfigSpec struct {
	ConfigID   uint8
	KEMID      uint16
	PublicKey  []byte
	PublicName string
}

// BuildECHConfigList encodes a single-config ECHConfigList with the standard
// HKDF-SHA256 + AES-128-GCM suite.
func BuildECHConfigList(spec ECHConfigSpec) ([]byte, error) {
	if len(spec.PublicKey) == 0 || spec.PublicName == "" || len(spec.PublicName) > 255 {
		return nil, errors.New("an ECH config needs a public key and a public name")
	}
	kem := spec.KEMID
	if kem == 0 {
		kem = 0x0020 // DHKEM(X25519, HKDF-SHA256)
	}
	var body []byte
	body = append(body, spec.ConfigID)
	body = appendUint16(body, kem)
	body = appendUint16(body, uint16(len(spec.PublicKey)))
	body = append(body, spec.PublicKey...)
	body = appendUint16(body, 4) // one cipher suite
	body = appendUint16(body, 1) // HKDF-SHA256
	body = appendUint16(body, 1) // AES-128-GCM
	body = append(body, byte(len(spec.PublicName)))
	body = append(body, byte(len(spec.PublicName)))
	body = append(body, spec.PublicName...)
	body = appendUint16(body, 0) // no extensions

	var config []byte
	config = appendUint16(config, echVersion)
	config = appendUint16(config, uint16(len(body)))
	config = append(config, body...)

	list := appendUint16(nil, uint16(len(config)))
	return append(list, config...), nil
}

func appendUint16(out []byte, value uint16) []byte {
	return append(out, byte(value>>8), byte(value))
}
