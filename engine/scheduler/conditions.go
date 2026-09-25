package scheduler

import (
	"errors"
	"fmt"
	"sort"
	"time"
)

var (
	ErrInvalidRuntimeSnapshot = errors.New("invalid runtime snapshot")
	ErrInvalidConditionPolicy = errors.New("invalid condition policy")
	ErrInvalidSchedule        = errors.New("invalid schedule definition")
)

type PowerSource string

const (
	PowerUnknown  PowerSource = "unknown"
	PowerBattery  PowerSource = "battery"
	PowerAC       PowerSource = "ac"
	PowerUSB      PowerSource = "usb"
	PowerWireless PowerSource = "wireless"
)

type HoldReason string

const (
	HoldOffline               HoldReason = "offline"
	HoldMetered               HoldReason = "metered"
	HoldWiFiUnavailable       HoldReason = "wifi_unavailable"
	HoldNotCharging           HoldReason = "not_charging"
	HoldBatteryLow            HoldReason = "battery_low"
	HoldStorageLow            HoldReason = "storage_low"
	HoldPowerSourceDisallowed HoldReason = "power_source_disallowed"
	HoldScheduleClosed        HoldReason = "schedule_closed"
	HoldScheduleDisabled      HoldReason = "schedule_disabled"
)

type RuntimeSnapshot struct {
	ObservedAtUnixMS int64       `json:"observed_at_unix_ms"`
	Online           bool        `json:"online"`
	Metered          bool        `json:"metered"`
	WiFi             bool        `json:"wifi"`
	Charging         bool        `json:"charging"`
	BatteryPercent   int         `json:"battery_percent"`
	StorageFreeBytes int64       `json:"storage_free_bytes"`
	PowerSource      PowerSource `json:"power_source"`
}

type ConditionPolicy struct {
	RequireOnline       bool          `json:"require_online,omitempty"`
	AllowMetered        bool          `json:"allow_metered,omitempty"` // Deprecated compatibility alias; RequireUnmetered is authoritative.
	RequireUnmetered    bool          `json:"require_unmetered,omitempty"`
	RequireWiFi         bool          `json:"require_wifi,omitempty"`
	RequireCharging     bool          `json:"require_charging,omitempty"`
	MinBatteryPercent   int           `json:"min_battery_percent,omitempty"`
	MinStorageFreeBytes int64         `json:"min_storage_free_bytes,omitempty"`
	AllowedPowerSources []PowerSource `json:"allowed_power_sources,omitempty"`
}

type ConditionEvaluation struct {
	Eligible bool         `json:"eligible"`
	Holds    []HoldReason `json:"holds,omitempty"`
}

func EvaluateConditions(policy ConditionPolicy, snapshot RuntimeSnapshot) (ConditionEvaluation, error) {
	if err := validateConditionPolicy(policy); err != nil {
		return ConditionEvaluation{}, err
	}
	if err := validateRuntimeSnapshot(snapshot); err != nil {
		return ConditionEvaluation{}, err
	}
	holds := make([]HoldReason, 0, 7)
	if policy.RequireOnline && !snapshot.Online {
		holds = append(holds, HoldOffline)
	}
	if policy.RequireUnmetered && snapshot.Metered {
		holds = append(holds, HoldMetered)
	}
	if policy.RequireWiFi && !snapshot.WiFi {
		holds = append(holds, HoldWiFiUnavailable)
	}
	if policy.RequireCharging && !snapshot.Charging {
		holds = append(holds, HoldNotCharging)
	}
	if policy.MinBatteryPercent > 0 && snapshot.BatteryPercent < policy.MinBatteryPercent {
		holds = append(holds, HoldBatteryLow)
	}
	if policy.MinStorageFreeBytes > 0 && snapshot.StorageFreeBytes < policy.MinStorageFreeBytes {
		holds = append(holds, HoldStorageLow)
	}
	if len(policy.AllowedPowerSources) > 0 {
		allowed := false
		for _, src := range policy.AllowedPowerSources {
			if src == snapshot.PowerSource {
				allowed = true
				break
			}
		}
		if !allowed {
			holds = append(holds, HoldPowerSourceDisallowed)
		}
	}
	return ConditionEvaluation{Eligible: len(holds) == 0, Holds: holds}, nil
}

func validateRuntimeSnapshot(snapshot RuntimeSnapshot) error {
	if snapshot.BatteryPercent < 0 || snapshot.BatteryPercent > 100 || snapshot.StorageFreeBytes < 0 {
		return ErrInvalidRuntimeSnapshot
	}
	switch snapshot.PowerSource {
	case "", PowerUnknown, PowerBattery, PowerAC, PowerUSB, PowerWireless:
		return nil
	default:
		return fmt.Errorf("%w: power source %q", ErrInvalidRuntimeSnapshot, snapshot.PowerSource)
	}
}

func validateConditionPolicy(policy ConditionPolicy) error {
	if policy.MinBatteryPercent < 0 || policy.MinBatteryPercent > 100 || policy.MinStorageFreeBytes < 0 {
		return ErrInvalidConditionPolicy
	}
	seen := map[PowerSource]bool{}
	for _, src := range policy.AllowedPowerSources {
		switch src {
		case PowerUnknown, PowerBattery, PowerAC, PowerUSB, PowerWireless:
		default:
			return fmt.Errorf("%w: power source %q", ErrInvalidConditionPolicy, src)
		}
		if seen[src] {
			return fmt.Errorf("%w: duplicate power source %q", ErrInvalidConditionPolicy, src)
		}
		seen[src] = true
	}
	return nil
}

func canonicalConditionPolicy(policy ConditionPolicy) (ConditionPolicy, error) {
	if err := validateConditionPolicy(policy); err != nil {
		return ConditionPolicy{}, err
	}
	policy.AllowedPowerSources = append([]PowerSource(nil), policy.AllowedPowerSources...)
	sort.Slice(policy.AllowedPowerSources, func(i, j int) bool { return policy.AllowedPowerSources[i] < policy.AllowedPowerSources[j] })
	return policy, nil
}

func timeFromUnixMS(ms int64) time.Time {
	return time.Unix(0, ms*int64(time.Millisecond)).UTC()
}
