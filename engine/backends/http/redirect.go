package httpbackend

import (
	"net/netip"

	"github.com/subhra74/xdm/engine/domain/identity"
	domainrequest "github.com/subhra74/xdm/engine/domain/request"
	"github.com/subhra74/xdm/engine/security/redirect"
	"github.com/subhra74/xdm/engine/security/route"
	"github.com/subhra74/xdm/engine/security/transportpolicy"
)

// RedirectIntent reuses the canonical security redirect state machine and then
// projects the approved step back into NetworkIntent. No backend-local redirect
// policy is allowed to reinterpret body replayability or credential scope.
func RedirectIntent(
	reqID identity.RequestID,
	current domainrequest.NetworkIntent,
	chain redirect.Chain,
	status int,
	location string,
	addresses []netip.Addr,
	approvals []route.Approval,
	clear transportpolicy.CleartextDecision,
) (domainrequest.NetworkIntent, redirect.Chain, error) {
	step, err := redirect.Next(reqID, current.Resource, current, chain, status, location, addresses, approvals, clear)
	if err != nil {
		return domainrequest.NetworkIntent{}, chain, err
	}
	next := current
	next.TransportURL = step.To
	next.Method = step.Method
	next.Body = step.Body
	next.Credentials = step.Credentials
	normalized, err := domainrequest.NewNetworkIntent(next)
	if err != nil {
		return domainrequest.NetworkIntent{}, chain, err
	}
	return normalized, step.Chain, nil
}
