package runtime

import (
	"context"
	"encoding/json"
	"errors"
	"strings"

	"github.com/subhra74/xdm/engine/domain/publication"
	"github.com/subhra74/xdm/engine/ops"
	"github.com/subhra74/xdm/engine/runtime/command"
	"github.com/subhra74/xdm/engine/runtime/event"
	"github.com/subhra74/xdm/engine/runtime/platform"
)

const (
	DesktopPublicationCommitKind = "desktop.publication_commit"
	DesktopSystemProxyKind       = "desktop.system_proxy"
	DesktopSecretLookupKind      = "desktop.secret_lookup"
	DesktopWakeupKind            = "desktop.wakeup"
	DesktopWakeDueKind           = "desktop.wakeup_due"
)

type DesktopPublicationRequest struct {
	Commit          publication.CommitRequest `json:"commit"`
	StagingPath     string                    `json:"staging_path"`
	DestinationPath string                    `json:"destination_path"`
	CollisionPolicy string                    `json:"collision_policy,omitempty"`
}

type DesktopSystemProxyRequest struct {
	TargetURL string `json:"target_url,omitempty"`
}

type DesktopSecretLookupRequest struct {
	Ref ops.SecretRef `json:"secret_ref"`
}

type DesktopWakeupRequest struct {
	WakeID     string `json:"wake_id"`
	DeadlineMS int64  `json:"deadline_ms"`
	Reason     string `json:"reason,omitempty"`
}

func (e *Engine) desktopPublicationCommitHandler(ctx context.Context, inv *Invocation, env command.Envelope) error {
	var req DesktopPublicationRequest
	if err := json.Unmarshal(env.Payload, &req); err != nil {
		return err
	}
	if err := req.Commit.Validate(); err != nil {
		return err
	}
	if strings.TrimSpace(req.StagingPath) == "" || strings.TrimSpace(req.DestinationPath) == "" {
		return publication.ErrInvalidRequest
	}
	payload, err := json.Marshal(req)
	if err != nil {
		return err
	}
	reply, err := inv.PlatformRequest(ctx, platform.Publication, platform.Refs{
		PublicationID: req.Commit.PublicationID,
	}, payload)
	if err != nil {
		return err
	}
	if !reply.OK {
		if reply.ErrorCode == "" {
			return errors.New("desktop publication rejected")
		}
		return errors.New("desktop publication rejected: " + reply.ErrorCode)
	}
	var receipt publication.Receipt
	if err := json.Unmarshal(reply.Payload, &receipt); err != nil {
		return err
	}
	if err := receipt.Validate(); err != nil {
		return err
	}
	out, _ := json.Marshal(receipt)
	_, err = inv.Emit(event.Durable, "desktop.publication.committed", out, "")
	return err
}

func (e *Engine) desktopSystemProxyHandler(ctx context.Context, inv *Invocation, env command.Envelope) error {
	var req DesktopSystemProxyRequest
	if len(env.Payload) > 0 {
		if err := json.Unmarshal(env.Payload, &req); err != nil {
			return err
		}
	}
	payload, _ := json.Marshal(req)
	reply, err := inv.PlatformRequest(ctx, platform.SystemProxy, platform.Refs{}, payload)
	if err != nil {
		return err
	}
	if !reply.OK {
		return errors.New("desktop system proxy unavailable: " + reply.ErrorCode)
	}
	_, err = inv.Emit(event.Telemetry, "desktop.system_proxy.projection", reply.Payload, "desktop.system_proxy")
	return err
}

func (e *Engine) desktopSecretLookupHandler(ctx context.Context, inv *Invocation, env command.Envelope) error {
	var req DesktopSecretLookupRequest
	if err := json.Unmarshal(env.Payload, &req); err != nil {
		return err
	}
	if err := req.Ref.Validate(); err != nil {
		return err
	}
	payload, _ := json.Marshal(req)
	reply, err := inv.PlatformRequest(ctx, platform.SecretLookup, platform.Refs{}, payload)
	if err != nil {
		return err
	}
	if !reply.OK {
		return errors.New("desktop secret lookup failed: " + reply.ErrorCode)
	}
	// Never publish the resolved secret on the event stream.  Only a safe acknowledgement leaves Go.
	safe, _ := json.Marshal(map[string]any{
		"secret_ref": req.Ref.Canonical(),
		"resolved":   true,
	})
	_, err = inv.Emit(event.Operational, "desktop.secret.resolved", safe, "")
	return err
}

func (e *Engine) desktopWakeupHandler(ctx context.Context, inv *Invocation, env command.Envelope) error {
	var req DesktopWakeupRequest
	if err := json.Unmarshal(env.Payload, &req); err != nil {
		return err
	}
	if strings.TrimSpace(req.WakeID) == "" || req.DeadlineMS <= 0 {
		return errors.New("invalid desktop wakeup request")
	}
	payload, _ := json.Marshal(req)
	reply, err := inv.PlatformRequest(ctx, platform.Wakeup, platform.Refs{}, payload)
	if err != nil {
		return err
	}
	if !reply.OK {
		return errors.New("desktop wakeup registration failed: " + reply.ErrorCode)
	}
	_, err = inv.Emit(event.Operational, "desktop.wakeup.registered", reply.Payload, "")
	return err
}

func (e *Engine) desktopWakeDueHandler(ctx context.Context, inv *Invocation, env command.Envelope) error {
	var req DesktopWakeupRequest
	if err := json.Unmarshal(env.Payload, &req); err != nil {
		return err
	}
	if strings.TrimSpace(req.WakeID) == "" {
		return errors.New("invalid desktop wake due fact")
	}
	_, err := inv.Emit(event.Durable, "desktop.wakeup.due", env.Payload, "")
	return err
}
