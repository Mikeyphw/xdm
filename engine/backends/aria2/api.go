package aria2

import (
	"context"
	"errors"
	"fmt"
	"net/url"
	"strconv"
	"strings"
	"time"
)

type Version struct {
	Version         string   `json:"version"`
	EnabledFeatures []string `json:"enabledFeatures"`
}

type Health struct {
	Healthy         bool
	Version         string
	EnabledFeatures []string
	Latency         time.Duration
}

type Status string

const (
	StatusActive   Status = "active"
	StatusWaiting  Status = "waiting"
	StatusPaused   Status = "paused"
	StatusError    Status = "error"
	StatusComplete Status = "complete"
	StatusRemoved  Status = "removed"
	StatusUnknown  Status = "unknown"
)

type URIState struct {
	URI    string `json:"uri"`
	Status string `json:"status"`
}

type File struct {
	Path string     `json:"path"`
	URIs []URIState `json:"uris"`
}

type rawTask struct {
	GID             string   `json:"gid"`
	Status          string   `json:"status"`
	TotalLength     string   `json:"totalLength"`
	CompletedLength string   `json:"completedLength"`
	DownloadSpeed   string   `json:"downloadSpeed"`
	Directory       string   `json:"dir"`
	Files           []File   `json:"files"`
	ErrorCode       string   `json:"errorCode"`
	ErrorMessage    string   `json:"errorMessage"`
	FollowedBy      []string `json:"followedBy"`
	Following       string   `json:"following"`
	BelongsTo       string   `json:"belongsTo"`
}

type Task struct {
	GID             string
	Status          Status
	TotalLength     int64
	CompletedLength int64
	DownloadSpeed   int64
	Directory       string
	Files           []File
	ErrorCode       string
	ErrorMessage    string
	FollowedBy      []string
	Following       string
	BelongsTo       string
}

var taskKeys = []string{
	"gid", "status", "totalLength", "completedLength", "downloadSpeed", "dir", "files",
	"errorCode", "errorMessage", "followedBy", "following", "belongsTo",
}

func (c *Client) GetVersion(ctx context.Context) (Version, error) {
	var version Version
	if err := c.invoke(ctx, "aria2.getVersion", nil, &version); err != nil {
		return Version{}, err
	}
	if strings.TrimSpace(version.Version) == "" {
		return Version{}, &ProtocolError{Kind: ErrMalformedResponse, Method: "aria2.getVersion", Detail: "result has no version"}
	}
	return version, nil
}

func (c *Client) Health(ctx context.Context) (Health, error) {
	started := time.Now()
	version, err := c.GetVersion(ctx)
	if err != nil {
		return Health{Latency: time.Since(started)}, err
	}
	return Health{Healthy: true, Version: version.Version, EnabledFeatures: append([]string(nil), version.EnabledFeatures...), Latency: time.Since(started)}, nil
}

func (c *Client) AddURI(ctx context.Context, uris []string, options Options) (string, error) {
	if len(uris) == 0 {
		return "", fmt.Errorf("%w: at least one URI is required", ErrInvalidArgument)
	}
	clean := make([]string, 0, len(uris))
	for _, raw := range uris {
		parsed, err := url.Parse(strings.TrimSpace(raw))
		if err != nil || parsed == nil || !parsed.IsAbs() || parsed.Scheme == "" {
			return "", fmt.Errorf("%w: invalid source URI", ErrInvalidArgument)
		}
		clean = append(clean, parsed.String())
	}
	if options == nil {
		options = Options{}
	}
	if err := options.validate(); err != nil {
		return "", err
	}
	var gid string
	if err := c.invoke(ctx, "aria2.addUri", []any{clean, options}, &gid); err != nil {
		return "", err
	}
	if strings.TrimSpace(gid) == "" {
		return "", &ProtocolError{Kind: ErrMalformedResponse, Method: "aria2.addUri", Detail: "empty GID"}
	}
	return gid, nil
}

func (c *Client) TellStatus(ctx context.Context, gid string) (Task, error) {
	if err := validateGID(gid); err != nil {
		return Task{}, err
	}
	var raw rawTask
	if err := c.invoke(ctx, "aria2.tellStatus", []any{gid, taskKeys}, &raw); err != nil {
		return Task{}, err
	}
	return parseTask("aria2.tellStatus", raw)
}

func (c *Client) TellActive(ctx context.Context) ([]Task, error) {
	return c.list(ctx, "aria2.tellActive", []any{taskKeys})
}

func (c *Client) TellWaiting(ctx context.Context, offset, count int) ([]Task, error) {
	if count < 1 {
		count = 1
	}
	if count > maxListCount {
		count = maxListCount
	}
	return c.list(ctx, "aria2.tellWaiting", []any{offset, count, taskKeys})
}

func (c *Client) TellStopped(ctx context.Context, offset, count int) ([]Task, error) {
	if count < 1 {
		count = 1
	}
	if count > maxListCount {
		count = maxListCount
	}
	return c.list(ctx, "aria2.tellStopped", []any{offset, count, taskKeys})
}

func (c *Client) list(ctx context.Context, method string, params []any) ([]Task, error) {
	var raws []rawTask
	if err := c.invoke(ctx, method, params, &raws); err != nil {
		return nil, err
	}
	out := make([]Task, 0, len(raws))
	for _, raw := range raws {
		task, err := parseTask(method, raw)
		if err != nil {
			return nil, err
		}
		out = append(out, task)
	}
	return out, nil
}

func (c *Client) Pause(ctx context.Context, gid string, force bool) error {
	method := "aria2.pause"
	if force {
		method = "aria2.forcePause"
	}
	return c.control(ctx, method, gid)
}

func (c *Client) Unpause(ctx context.Context, gid string) error {
	return c.control(ctx, "aria2.unpause", gid)
}

func (c *Client) Remove(ctx context.Context, gid string, force bool) error {
	method := "aria2.remove"
	if force {
		method = "aria2.forceRemove"
	}
	return c.control(ctx, method, gid)
}

func (c *Client) control(ctx context.Context, method, gid string) error {
	if err := validateGID(gid); err != nil {
		return err
	}
	var returned string
	if err := c.invoke(ctx, method, []any{gid}, &returned); err != nil {
		return err
	}
	if returned != "" && !strings.EqualFold(returned, gid) {
		return &ProtocolError{Kind: ErrMalformedResponse, Method: method, Detail: "returned GID differs from requested GID"}
	}
	return nil
}

func (c *Client) ChangeOptions(ctx context.Context, gid string, options Options) error {
	if err := validateGID(gid); err != nil {
		return err
	}
	if len(options) == 0 {
		return fmt.Errorf("%w: task options are empty", ErrInvalidArgument)
	}
	if err := options.validate(); err != nil {
		return err
	}
	var result string
	if err := c.invoke(ctx, "aria2.changeOption", []any{gid, options}, &result); err != nil {
		return err
	}
	return requireOK("aria2.changeOption", result)
}

func (c *Client) ChangeGlobalOptions(ctx context.Context, options Options) error {
	if len(options) == 0 {
		return fmt.Errorf("%w: global options are empty", ErrInvalidArgument)
	}
	if err := options.validate(); err != nil {
		return err
	}
	var result string
	if err := c.invoke(ctx, "aria2.changeGlobalOption", []any{options}, &result); err != nil {
		return err
	}
	return requireOK("aria2.changeGlobalOption", result)
}

func (c *Client) GetGlobalOptions(ctx context.Context) (map[string]string, error) {
	var result map[string]string
	if err := c.invoke(ctx, "aria2.getGlobalOption", nil, &result); err != nil {
		return nil, err
	}
	if result == nil {
		result = map[string]string{}
	}
	return result, nil
}

func (c *Client) SaveSession(ctx context.Context) error {
	var result string
	if err := c.invoke(ctx, "aria2.saveSession", nil, &result); err != nil {
		return err
	}
	return requireOK("aria2.saveSession", result)
}

func (c *Client) Shutdown(ctx context.Context, force bool) error {
	method := "aria2.shutdown"
	if force {
		method = "aria2.forceShutdown"
	}
	var result string
	if err := c.invoke(ctx, method, nil, &result); err != nil {
		return err
	}
	return requireOK(method, result)
}

func validateGID(gid string) error {
	if strings.TrimSpace(gid) == "" || strings.ContainsAny(gid, "\r\n\x00") {
		return fmt.Errorf("%w: invalid GID", ErrInvalidArgument)
	}
	return nil
}

func requireOK(method, result string) error {
	if strings.EqualFold(strings.TrimSpace(result), "OK") {
		return nil
	}
	return &ProtocolError{Kind: ErrMalformedResponse, Method: method, Detail: "expected OK result"}
}

func parseTask(method string, raw rawTask) (Task, error) {
	if err := validateGID(raw.GID); err != nil {
		return Task{}, &ProtocolError{Kind: ErrMalformedResponse, Method: method, Detail: "task has no GID"}
	}
	total, err := parseNonNegative(raw.TotalLength)
	if err != nil {
		return Task{}, &ProtocolError{Kind: ErrMalformedResponse, Method: method, Detail: "invalid totalLength"}
	}
	completed, err := parseNonNegative(raw.CompletedLength)
	if err != nil {
		return Task{}, &ProtocolError{Kind: ErrMalformedResponse, Method: method, Detail: "invalid completedLength"}
	}
	speed, err := parseNonNegative(raw.DownloadSpeed)
	if err != nil {
		return Task{}, &ProtocolError{Kind: ErrMalformedResponse, Method: method, Detail: "invalid downloadSpeed"}
	}
	if total > 0 && completed > total {
		return Task{}, &ProtocolError{Kind: ErrMalformedResponse, Method: method, Detail: "completedLength exceeds totalLength"}
	}
	status := Status(strings.ToLower(strings.TrimSpace(raw.Status)))
	switch status {
	case StatusActive, StatusWaiting, StatusPaused, StatusError, StatusComplete, StatusRemoved:
	default:
		status = StatusUnknown
	}
	return Task{
		GID: raw.GID, Status: status, TotalLength: total, CompletedLength: completed,
		DownloadSpeed: speed, Directory: raw.Directory, Files: append([]File(nil), raw.Files...),
		ErrorCode: raw.ErrorCode, ErrorMessage: raw.ErrorMessage,
		FollowedBy: append([]string(nil), raw.FollowedBy...), Following: raw.Following, BelongsTo: raw.BelongsTo,
	}, nil
}

func parseNonNegative(raw string) (int64, error) {
	if strings.TrimSpace(raw) == "" {
		return 0, nil
	}
	value, err := strconv.ParseInt(raw, 10, 64)
	if err != nil || value < 0 {
		return 0, errors.New("not a non-negative integer")
	}
	return value, nil
}
