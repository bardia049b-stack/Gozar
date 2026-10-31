package tundev

import (
	"net"
	"strconv"
	"strings"
)

func windowsDefaultRoute(output string, indexes map[string]int) (string, string) {
	bestMetric := int(^uint(0) >> 1)
	bestGateway, bestIndex := "", ""
	for _, line := range strings.Split(output, "\n") {
		fields := strings.Fields(line)
		if len(fields) < 5 || fields[0] != "0.0.0.0" || fields[1] != "0.0.0.0" {
			continue
		}
		address := net.ParseIP(fields[3])
		if address == nil || address.To4() == nil {
			continue
		}
		index, ok := indexes[address.String()]
		if !ok || index <= 0 {
			continue
		}
		metric, err := strconv.Atoi(fields[4])
		if err != nil || metric < 0 || metric >= bestMetric {
			continue
		}
		gateway := net.ParseIP(fields[2])
		if gateway == nil {
			gateway = net.IPv4zero
		} else if gateway.To4() == nil {
			continue
		}
		bestGateway, bestIndex, bestMetric = gateway.String(), strconv.Itoa(index), metric
	}
	return bestGateway, bestIndex
}
