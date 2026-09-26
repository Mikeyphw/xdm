package ops

import (
	"encoding/json"
	"errors"
	"fmt"
	"sort"
	"strings"
)

const EngineSettingsVersion = 1

var (
	ErrInvalidSettings       = errors.New("invalid engine settings")
	ErrFutureSettingsVersion = errors.New("future engine settings version")
)

type Owner string

const (
	OwnerEngine           Owner = "engine"
	OwnerAndroidHost      Owner = "android_host"
	OwnerDesktopHost      Owner = "desktop_host"
	OwnerPresentationOnly Owner = "presentation_only"
)

type LegacySettingMapping struct {
	Key        string `json:"key"`
	Owner      Owner  `json:"owner"`
	Reason     string `json:"reason"`
	Donor      string `json:"donor"`
	EnginePath string `json:"engine_path,omitempty"`
}

type RetrySettings struct {
	MaxAttempts int `json:"max_attempts"`
	BaseDelayMS int `json:"base_delay_ms"`
	MaxDelayMS  int `json:"max_delay_ms"`
}

type ConnectionSettings struct {
	MaxConcurrentGlobal  int `json:"max_concurrent_global"`
	MaxConcurrentPerHost int `json:"max_concurrent_per_host"`
}

type BandwidthSettings struct {
	MaxBytesPerSecond int64 `json:"max_bytes_per_second"`
}

type BackendPreferenceSettings struct {
	Order []string `json:"order"`
}

type QueuePolicySettings struct {
	MaxActive            int `json:"max_active"`
	FairnessAgingSeconds int `json:"fairness_aging_seconds"`
}

type NetworkSecuritySettings struct {
	AllowPrivateNetworks bool   `json:"allow_private_networks"`
	CleartextPolicy      string `json:"cleartext_policy"`
}

type MediaPreferenceSettings struct {
	PreferredContainer string   `json:"preferred_container"`
	MaxHeight          int      `json:"max_height"`
	PreferredLanguages []string `json:"preferred_languages"`
}

type RetentionSettings struct {
	EventsMaxCount      int   `json:"events_max_count"`
	EventsMaxBytes      int64 `json:"events_max_bytes"`
	EventsMaxAgeDays    int   `json:"events_max_age_days"`
	ExternalLogMaxBytes int   `json:"external_log_max_bytes"`
}

type EngineSettings struct {
	Version            int                       `json:"version"`
	Retry              RetrySettings             `json:"retry"`
	Connections        ConnectionSettings        `json:"connections"`
	Bandwidth          BandwidthSettings         `json:"bandwidth"`
	BackendPreferences BackendPreferenceSettings `json:"backend_preferences"`
	QueuePolicy        QueuePolicySettings       `json:"queue_policy"`
	NetworkSecurity    NetworkSecuritySettings   `json:"network_security"`
	MediaPreferences   MediaPreferenceSettings   `json:"media_preferences"`
	Retention          RetentionSettings         `json:"retention"`
}

type SettingsParseResult struct {
	Settings      EngineSettings             `json:"settings,omitempty"`
	FutureVersion int                        `json:"future_version,omitempty"`
	FutureRaw     map[string]json.RawMessage `json:"future_raw,omitempty"`
}

func LegacySettingsInventory() []LegacySettingMapping {
	items := []LegacySettingMapping{
		{Key: "retry.maxAttempts", Owner: OwnerEngine, Reason: "affects execution semantics", Donor: "android_retry_policy", EnginePath: "retry.max_attempts"},
		{Key: "retry.baseDelayMs", Owner: OwnerEngine, Reason: "affects execution semantics", Donor: "desktop_retry_policy", EnginePath: "retry.base_delay_ms"},
		{Key: "connections.maxGlobal", Owner: OwnerEngine, Reason: "shared scheduler/resource arbitration", Donor: "android_queue_coordinator", EnginePath: "connections.max_concurrent_global"},
		{Key: "connections.maxPerHost", Owner: OwnerEngine, Reason: "network execution semantics", Donor: "desktop_connection_limiter", EnginePath: "connections.max_concurrent_per_host"},
		{Key: "bandwidth.limit", Owner: OwnerEngine, Reason: "shared scheduler/resource arbitration", Donor: "android_bandwidth_limiter", EnginePath: "bandwidth.max_bytes_per_second"},
		{Key: "backend.order", Owner: OwnerEngine, Reason: "backend selection is now Go-owned", Donor: "android_backend_order", EnginePath: "backend_preferences.order"},
		{Key: "queue.maxActive", Owner: OwnerEngine, Reason: "queue execution semantics", Donor: "android_scheduler", EnginePath: "queue_policy.max_active"},
		{Key: "network.allowPrivate", Owner: OwnerEngine, Reason: "security policy is shared and typed", Donor: "android_private_network_policy", EnginePath: "network_security.allow_private_networks"},
		{Key: "media.preferredContainer", Owner: OwnerEngine, Reason: "media selection/post-processing semantics", Donor: "android_media_settings", EnginePath: "media_preferences.preferred_container"},
		{Key: "diagnostics.retentionDays", Owner: OwnerEngine, Reason: "diagnostic data lifecycle is shared", Donor: "desktop_log_retention", EnginePath: "retention.events_max_age_days"},
		{Key: "android.notificationChannel", Owner: OwnerAndroidHost, Reason: "Android presentation and platform notification routing", Donor: "android_notifications"},
		{Key: "android.storagePickerMode", Owner: OwnerAndroidHost, Reason: "host UI and SAF integration", Donor: "android_storage_ui"},
		{Key: "desktop.theme", Owner: OwnerDesktopHost, Reason: "desktop presentation preference", Donor: "desktop_settings"},
		{Key: "desktop.trayBehavior", Owner: OwnerDesktopHost, Reason: "desktop shell integration", Donor: "desktop_tray"},
		{Key: "list.rowDensity", Owner: OwnerPresentationOnly, Reason: "pure UI presentation", Donor: "android_download_list"},
		{Key: "animation.enabled", Owner: OwnerPresentationOnly, Reason: "pure UI presentation", Donor: "desktop_animation"},
	}
	sort.Slice(items, func(i, j int) bool { return items[i].Key < items[j].Key })
	return items
}

func DefaultEngineSettings() EngineSettings {
	return EngineSettings{
		Version:            EngineSettingsVersion,
		Retry:              RetrySettings{MaxAttempts: 3, BaseDelayMS: 1000, MaxDelayMS: 30000},
		Connections:        ConnectionSettings{MaxConcurrentGlobal: 4, MaxConcurrentPerHost: 2},
		Bandwidth:          BandwidthSettings{MaxBytesPerSecond: 0},
		BackendPreferences: BackendPreferenceSettings{Order: []string{"native-http", "aria2", "ftp"}},
		QueuePolicy:        QueuePolicySettings{MaxActive: 4, FairnessAgingSeconds: 60},
		NetworkSecurity:    NetworkSecuritySettings{AllowPrivateNetworks: false, CleartextPolicy: "deny_without_approval"},
		MediaPreferences:   MediaPreferenceSettings{PreferredContainer: "mp4", MaxHeight: 1080, PreferredLanguages: []string{"und"}},
		Retention:          RetentionSettings{EventsMaxCount: 10000, EventsMaxBytes: 16 * 1024 * 1024, EventsMaxAgeDays: 14, ExternalLogMaxBytes: 64 * 1024},
	}
}

func ParseEngineSettingsJSON(data []byte) (SettingsParseResult, error) {
	var meta struct {
		Version int `json:"version"`
	}
	if err := json.Unmarshal(data, &meta); err != nil {
		return SettingsParseResult{}, fmt.Errorf("%w: json", ErrInvalidSettings)
	}
	if meta.Version == 0 {
		settings, err := MigrateEngineSettingsV0(data)
		return SettingsParseResult{Settings: settings}, err
	}
	if meta.Version > EngineSettingsVersion {
		var raw map[string]json.RawMessage
		if err := json.Unmarshal(data, &raw); err != nil {
			return SettingsParseResult{}, fmt.Errorf("%w: future json", ErrInvalidSettings)
		}
		return SettingsParseResult{FutureVersion: meta.Version, FutureRaw: raw}, ErrFutureSettingsVersion
	}
	var settings EngineSettings
	if err := json.Unmarshal(data, &settings); err != nil {
		return SettingsParseResult{}, fmt.Errorf("%w: settings", ErrInvalidSettings)
	}
	if err := settings.Validate(); err != nil {
		return SettingsParseResult{}, err
	}
	return SettingsParseResult{Settings: settings}, nil
}

func MigrateEngineSettingsV0(data []byte) (EngineSettings, error) {
	settings := DefaultEngineSettings()
	var raw struct {
		MaxRetries      *int     `json:"maxRetries"`
		Concurrent      *int     `json:"concurrent"`
		PerHost         *int     `json:"perHost"`
		BackendOrder    []string `json:"backendOrder"`
		BandwidthLimit  *int64   `json:"bandwidthLimit"`
		RetentionDays   *int     `json:"retentionDays"`
		PreferredFormat string   `json:"preferredFormat"`
	}
	if err := json.Unmarshal(data, &raw); err != nil {
		return EngineSettings{}, fmt.Errorf("%w: legacy json", ErrInvalidSettings)
	}
	if raw.MaxRetries != nil {
		settings.Retry.MaxAttempts = *raw.MaxRetries
	}
	if raw.Concurrent != nil {
		settings.Connections.MaxConcurrentGlobal = *raw.Concurrent
		settings.QueuePolicy.MaxActive = *raw.Concurrent
	}
	if raw.PerHost != nil {
		settings.Connections.MaxConcurrentPerHost = *raw.PerHost
	}
	if raw.BackendOrder != nil {
		settings.BackendPreferences.Order = append([]string(nil), raw.BackendOrder...)
	}
	if raw.BandwidthLimit != nil {
		settings.Bandwidth.MaxBytesPerSecond = *raw.BandwidthLimit
	}
	if raw.RetentionDays != nil {
		settings.Retention.EventsMaxAgeDays = *raw.RetentionDays
	}
	if raw.PreferredFormat != "" {
		settings.MediaPreferences.PreferredContainer = raw.PreferredFormat
	}
	if err := settings.Validate(); err != nil {
		return EngineSettings{}, err
	}
	return settings, nil
}

func (s EngineSettings) Validate() error {
	if s.Version != EngineSettingsVersion {
		return fmt.Errorf("%w: version", ErrInvalidSettings)
	}
	if s.Retry.MaxAttempts < 0 || s.Retry.MaxAttempts > 100 {
		return fmt.Errorf("%w: retry.max_attempts", ErrInvalidSettings)
	}
	if s.Retry.BaseDelayMS < 0 || s.Retry.MaxDelayMS < 0 || (s.Retry.MaxDelayMS > 0 && s.Retry.BaseDelayMS > s.Retry.MaxDelayMS) {
		return fmt.Errorf("%w: retry.delay", ErrInvalidSettings)
	}
	if s.Connections.MaxConcurrentGlobal <= 0 || s.Connections.MaxConcurrentPerHost <= 0 || s.Connections.MaxConcurrentPerHost > s.Connections.MaxConcurrentGlobal {
		return fmt.Errorf("%w: connections", ErrInvalidSettings)
	}
	if s.Bandwidth.MaxBytesPerSecond < 0 {
		return fmt.Errorf("%w: bandwidth", ErrInvalidSettings)
	}
	if len(s.BackendPreferences.Order) == 0 {
		return fmt.Errorf("%w: backend_preferences.order", ErrInvalidSettings)
	}
	seen := map[string]bool{}
	for _, backend := range s.BackendPreferences.Order {
		b := strings.TrimSpace(backend)
		if b == "" || seen[b] || !allowedBackend(b) {
			return fmt.Errorf("%w: backend_preferences.order", ErrInvalidSettings)
		}
		seen[b] = true
	}
	if s.QueuePolicy.MaxActive <= 0 || s.QueuePolicy.FairnessAgingSeconds < 0 || s.QueuePolicy.MaxActive > s.Connections.MaxConcurrentGlobal {
		return fmt.Errorf("%w: queue_policy", ErrInvalidSettings)
	}
	switch s.NetworkSecurity.CleartextPolicy {
	case "deny", "deny_without_approval", "allow_for_public", "allow_with_approval":
	default:
		return fmt.Errorf("%w: network_security.cleartext_policy", ErrInvalidSettings)
	}
	if s.MediaPreferences.MaxHeight < 0 || s.MediaPreferences.MaxHeight > 16384 {
		return fmt.Errorf("%w: media_preferences.max_height", ErrInvalidSettings)
	}
	if strings.TrimSpace(s.MediaPreferences.PreferredContainer) == "" {
		return fmt.Errorf("%w: media_preferences.preferred_container", ErrInvalidSettings)
	}
	if s.Retention.EventsMaxCount <= 0 || s.Retention.EventsMaxBytes <= 0 || s.Retention.EventsMaxAgeDays <= 0 || s.Retention.ExternalLogMaxBytes <= 0 {
		return fmt.Errorf("%w: retention", ErrInvalidSettings)
	}
	return nil
}

func allowedBackend(name string) bool {
	switch name {
	case "native-http", "aria2", "ftp", "ftps", "hls", "dash":
		return true
	default:
		return false
	}
}

func CanonicalSettingsJSON(settings EngineSettings) ([]byte, error) {
	if err := settings.Validate(); err != nil {
		return nil, err
	}
	return json.MarshalIndent(settings, "", "  ")
}
