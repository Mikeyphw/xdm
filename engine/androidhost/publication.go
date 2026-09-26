package androidhost

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"path/filepath"
	"sort"
	"strings"
	"sync"

	"github.com/subhra74/xdm/engine/domain/publication"
)

var (
	ErrInvalidDestination      = errors.New("invalid Android publication destination")
	ErrPermissionLost          = errors.New("android publication permission lost")
	ErrInsufficientSpace       = errors.New("android publication insufficient storage")
	ErrPublicationCollision    = errors.New("android publication collision")
	ErrProviderCommitAmbiguous = errors.New("android publication provider commit ambiguous")
	ErrProviderWriteFailed     = errors.New("android publication provider write failed")
	ErrReceiptNotFound         = errors.New("android publication receipt not found")
)

type AndroidDestinationKind string

const (
	DestinationDocumentTree AndroidDestinationKind = "document_tree"
	DestinationMediaStore   AndroidDestinationKind = "mediastore"
)

type AndroidCollisionPolicy string

const (
	CollisionFail    AndroidCollisionPolicy = "fail"
	CollisionReplace AndroidCollisionPolicy = "replace"
	CollisionRename  AndroidCollisionPolicy = "rename"
)

type AndroidDestinationSpec struct {
	Kind          AndroidDestinationKind `json:"kind"`
	TreeURI       string                 `json:"tree_uri,omitempty"`
	CollectionURI string                 `json:"collection_uri,omitempty"`
	RelativePath  string                 `json:"relative_path,omitempty"`
	DisplayName   string                 `json:"display_name"`
	MIMEType      string                 `json:"mime_type"`
	PermissionID  string                 `json:"permission_id,omitempty"`
	Collision     AndroidCollisionPolicy `json:"collision"`
}

func (s AndroidDestinationSpec) Validate() error {
	if strings.TrimSpace(s.DisplayName) == "" || strings.Contains(s.DisplayName, "/") || strings.TrimSpace(s.MIMEType) == "" {
		return ErrInvalidDestination
	}
	switch s.Collision {
	case CollisionFail, CollisionReplace, CollisionRename:
	default:
		return ErrInvalidDestination
	}
	switch s.Kind {
	case DestinationDocumentTree:
		if strings.TrimSpace(s.TreeURI) == "" || strings.TrimSpace(s.PermissionID) == "" {
			return ErrInvalidDestination
		}
	case DestinationMediaStore:
		if strings.TrimSpace(s.CollectionURI) == "" {
			return ErrInvalidDestination
		}
	default:
		return ErrInvalidDestination
	}
	if strings.Contains(s.RelativePath, "..") || filepath.IsAbs(s.RelativePath) {
		return ErrInvalidDestination
	}
	return nil
}

type AndroidProviderTarget struct {
	Scheme        string `json:"scheme"`
	ParentURI     string `json:"parent_uri"`
	RelativePath  string `json:"relative_path,omitempty"`
	DisplayName   string `json:"display_name"`
	MIMEType      string `json:"mime_type"`
	RequiresGrant bool   `json:"requires_grant"`
	PermissionID  string `json:"permission_id,omitempty"`
}

func TranslateAndroidDestination(s AndroidDestinationSpec) (AndroidProviderTarget, error) {
	if err := s.Validate(); err != nil {
		return AndroidProviderTarget{}, err
	}
	switch s.Kind {
	case DestinationDocumentTree:
		return AndroidProviderTarget{Scheme: "saf", ParentURI: s.TreeURI, RelativePath: s.RelativePath, DisplayName: s.DisplayName, MIMEType: s.MIMEType, RequiresGrant: true, PermissionID: s.PermissionID}, nil
	case DestinationMediaStore:
		return AndroidProviderTarget{Scheme: "mediastore", ParentURI: s.CollectionURI, RelativePath: s.RelativePath, DisplayName: s.DisplayName, MIMEType: s.MIMEType, RequiresGrant: false}, nil
	default:
		return AndroidProviderTarget{}, ErrInvalidDestination
	}
}

type AndroidPermissionGrants struct{ granted map[string]bool }

func NewAndroidPermissionGrants(ids ...string) *AndroidPermissionGrants {
	g := &AndroidPermissionGrants{granted: map[string]bool{}}
	for _, id := range ids {
		if strings.TrimSpace(id) != "" {
			g.granted[id] = true
		}
	}
	return g
}

func (g *AndroidPermissionGrants) Has(id string) bool {
	if id == "" {
		return true
	}
	return g != nil && g.granted[id]
}

func (g *AndroidPermissionGrants) Revoke(id string) {
	if g != nil {
		delete(g.granted, id)
	}
}

type AndroidPublicationFault string

const (
	FaultNone                 AndroidPublicationFault = "none"
	FaultCrashAfterProvider   AndroidPublicationFault = "crash_after_provider_commit"
	FaultCrashBeforeReceipt   AndroidPublicationFault = "crash_before_receipt"
	FaultProviderWriteFailure AndroidPublicationFault = "provider_write_failure"
	FaultStorageFailure       AndroidPublicationFault = "storage_failure"
)

type AndroidPublicationRequest struct {
	Commit      publication.CommitRequest `json:"commit"`
	Destination AndroidDestinationSpec    `json:"destination"`
	SizeBytes   int64                     `json:"size_bytes"`
	ContentHash string                    `json:"content_hash,omitempty"`
	Fault       AndroidPublicationFault   `json:"fault,omitempty"`
}

func (r AndroidPublicationRequest) Validate() error {
	if err := r.Commit.Validate(); err != nil {
		return err
	}
	if err := r.Destination.Validate(); err != nil {
		return err
	}
	if r.SizeBytes < 0 {
		return ErrProviderWriteFailed
	}
	return nil
}

type AndroidPublicationReceipt struct {
	publication.Receipt
	Target          AndroidProviderTarget  `json:"target"`
	SizeBytes       int64                  `json:"size_bytes"`
	ContentHash     string                 `json:"content_hash"`
	CollisionPolicy AndroidCollisionPolicy `json:"collision_policy"`
	ProviderID      string                 `json:"provider_id"`
}

type AndroidPublicationStore struct {
	ReceiptsByKey map[string]AndroidPublicationReceipt `json:"receipts_by_key"`
	ProviderByKey map[string]AndroidPublicationReceipt `json:"provider_by_key"`
}

func NewAndroidPublicationStore() *AndroidPublicationStore {
	return &AndroidPublicationStore{ReceiptsByKey: map[string]AndroidPublicationReceipt{}, ProviderByKey: map[string]AndroidPublicationReceipt{}}
}

func (s *AndroidPublicationStore) Clone() *AndroidPublicationStore {
	out := NewAndroidPublicationStore()
	if s == nil {
		return out
	}
	for k, v := range s.ReceiptsByKey {
		out.ReceiptsByKey[k] = v
	}
	for k, v := range s.ProviderByKey {
		out.ProviderByKey[k] = v
	}
	return out
}

type AndroidPublicationBroker struct {
	mu          sync.Mutex
	store       *AndroidPublicationStore
	permissions *AndroidPermissionGrants
	capacity    int64
	used        int64
}

func NewAndroidPublicationBroker(store *AndroidPublicationStore, permissions *AndroidPermissionGrants, capacityBytes int64) *AndroidPublicationBroker {
	if store == nil {
		store = NewAndroidPublicationStore()
	}
	if permissions == nil {
		permissions = NewAndroidPermissionGrants()
	}
	return &AndroidPublicationBroker{store: store, permissions: permissions, capacity: capacityBytes}
}

func (b *AndroidPublicationBroker) AvailableSpace() int64 {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.capacity < b.used {
		return 0
	}
	return b.capacity - b.used
}

func (b *AndroidPublicationBroker) Commit(r AndroidPublicationRequest) (AndroidPublicationReceipt, error) {
	if err := r.Validate(); err != nil {
		return AndroidPublicationReceipt{}, err
	}
	b.mu.Lock()
	defer b.mu.Unlock()
	key := r.Commit.IdempotencyKey
	if receipt, ok := b.store.ReceiptsByKey[key]; ok {
		return receipt, nil
	}
	if receipt, ok := b.store.ProviderByKey[key]; ok {
		b.store.ReceiptsByKey[key] = receipt
		return receipt, nil
	}
	target, err := TranslateAndroidDestination(r.Destination)
	if err != nil {
		return AndroidPublicationReceipt{}, err
	}
	if target.RequiresGrant && !b.permissions.Has(target.PermissionID) {
		return AndroidPublicationReceipt{}, ErrPermissionLost
	}
	if r.Fault == FaultStorageFailure || (b.capacity > 0 && b.used+r.SizeBytes > b.capacity) {
		return AndroidPublicationReceipt{}, ErrInsufficientSpace
	}
	if existing, ok := b.findProviderCollision(target); ok {
		switch r.Destination.Collision {
		case CollisionFail:
			return AndroidPublicationReceipt{}, ErrPublicationCollision
		case CollisionReplace:
			b.used -= existing.SizeBytes
		case CollisionRename:
			target.DisplayName = b.rename(target.DisplayName)
		}
	}
	if r.Fault == FaultProviderWriteFailure {
		return AndroidPublicationReceipt{}, ErrProviderWriteFailed
	}
	receipt := makeAndroidReceipt(r, target)
	b.store.ProviderByKey[key] = receipt
	b.used += r.SizeBytes
	if r.Fault == FaultCrashBeforeReceipt {
		return AndroidPublicationReceipt{}, ErrProviderCommitAmbiguous
	}
	b.store.ReceiptsByKey[key] = receipt
	if r.Fault == FaultCrashAfterProvider {
		return AndroidPublicationReceipt{}, ErrProviderCommitAmbiguous
	}
	return receipt, nil
}

func (b *AndroidPublicationBroker) Inspect(req publication.InspectRequest) (AndroidPublicationReceipt, error) {
	if err := req.Validate(); err != nil {
		return AndroidPublicationReceipt{}, err
	}
	b.mu.Lock()
	defer b.mu.Unlock()
	key := req.IdempotencyKey
	if receipt, ok := b.store.ReceiptsByKey[key]; ok {
		return receipt, nil
	}
	if receipt, ok := b.store.ProviderByKey[key]; ok {
		b.store.ReceiptsByKey[key] = receipt
		return receipt, nil
	}
	return AndroidPublicationReceipt{}, ErrReceiptNotFound
}

func (b *AndroidPublicationBroker) Reconcile(req publication.InspectRequest) (publication.ReconcileAction, AndroidPublicationReceipt, error) {
	receipt, err := b.Inspect(req)
	if err == nil {
		return publication.ActionCommitEngine, receipt, nil
	}
	if errors.Is(err, ErrReceiptNotFound) {
		return publication.ActionRequestCommit, AndroidPublicationReceipt{}, nil
	}
	return publication.ActionNone, AndroidPublicationReceipt{}, err
}

func (b *AndroidPublicationBroker) DurableStore() *AndroidPublicationStore {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.store.Clone()
}

func (b *AndroidPublicationBroker) findProviderCollision(target AndroidProviderTarget) (AndroidPublicationReceipt, bool) {
	for _, receipt := range b.store.ProviderByKey {
		if receipt.Target.ParentURI == target.ParentURI && receipt.Target.RelativePath == target.RelativePath && receipt.Target.DisplayName == target.DisplayName {
			return receipt, true
		}
	}
	return AndroidPublicationReceipt{}, false
}

func (b *AndroidPublicationBroker) rename(name string) string {
	names := make(map[string]struct{})
	for _, receipt := range b.store.ProviderByKey {
		names[receipt.Target.DisplayName] = struct{}{}
	}
	ext := filepath.Ext(name)
	stem := strings.TrimSuffix(name, ext)
	for i := 1; ; i++ {
		candidate := fmt.Sprintf("%s (%d)%s", stem, i, ext)
		if _, ok := names[candidate]; !ok {
			return candidate
		}
	}
}

func makeAndroidReceipt(r AndroidPublicationRequest, target AndroidProviderTarget) AndroidPublicationReceipt {
	hash := strings.TrimSpace(r.ContentHash)
	if hash == "" {
		sum := sha256.Sum256([]byte(r.Commit.IdempotencyKey + ":" + target.ParentURI + ":" + target.DisplayName))
		hash = hex.EncodeToString(sum[:])
	}
	providerID := "android-provider:" + digestShort(r.Commit.IdempotencyKey+":"+target.ParentURI+":"+target.DisplayName)
	location := target.ParentURI
	if target.RelativePath != "" {
		location += "/" + strings.Trim(target.RelativePath, "/")
	}
	location += "/" + target.DisplayName
	receipt := publication.Receipt{PublicationID: r.Commit.PublicationID, ReceiptID: "android-receipt:" + digestShort(r.Commit.IdempotencyKey), Location: location}
	return AndroidPublicationReceipt{Receipt: receipt, Target: target, SizeBytes: r.SizeBytes, ContentHash: hash, CollisionPolicy: r.Destination.Collision, ProviderID: providerID}
}

func digestShort(value string) string {
	sum := sha256.Sum256([]byte(value))
	return hex.EncodeToString(sum[:8])
}

func ReceiptKeys(store *AndroidPublicationStore) []string {
	if store == nil {
		return nil
	}
	keys := make([]string, 0, len(store.ReceiptsByKey))
	for k := range store.ReceiptsByKey {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	return keys
}
