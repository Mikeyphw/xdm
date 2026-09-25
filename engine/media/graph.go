package media

import (
	"fmt"
	"sort"
	"strings"
)

type ProtectionClass string

const (
	ProtectionUnknown ProtectionClass = "unknown"
	ProtectionClear   ProtectionClass = "clear"
	ProtectionDRM     ProtectionClass = "drm"
	ProtectionKeyed   ProtectionClass = "keyed"
)

type TrackKind string

const (
	TrackVideo    TrackKind = "video"
	TrackAudio    TrackKind = "audio"
	TrackSubtitle TrackKind = "subtitle"
)

type MediaObservation struct {
	Envelope           CaptureEnvelope
	Resource           MediaResource
	Kind               ResourceKind
	Title              string
	Container          string
	Codec              string
	Language           string
	Bitrate            int
	Width              int
	Height             int
	EstimatedSizeBytes int64
	TrackKind          TrackKind
	RenditionGroup     string
	ManifestURL        string
	FragmentSetKey     string
	Protection         ProtectionClass
}

type MediaGraphLimits struct {
	MaxItems        int
	MaxSources      int
	MaxVariants     int
	MaxTracks       int
	MaxRenditions   int
	MaxFragmentSets int
}

type MediaGraph struct {
	limits       MediaGraphLimits
	Items        map[string]*MediaItem
	Sources      map[string]*MediaSource
	Variants     map[string]*MediaVariant
	Tracks       map[string]*MediaTrack
	Renditions   map[string]*MediaRendition
	Manifests    map[string]*MediaManifest
	FragmentSets map[string]*FragmentSet
	seenEvidence map[string]bool
}

type MediaItem struct {
	ID             string
	Title          string
	SourceIDs      []string
	VariantIDs     []string
	TrackIDs       []string
	RenditionIDs   []string
	ManifestIDs    []string
	FragmentSetIDs []string
	Protection     ProtectionClass
	Provenance     []string
}

type MediaSource struct {
	ID              string
	ResourceID      string
	TransportURL    string
	CanonicalURL    string
	Origin          string
	CredentialScope CredentialScope
	Provenance      []string
}

type MediaVariant struct {
	ID                 string
	ItemID             string
	SourceID           string
	Container          string
	Codec              string
	Bitrate            int
	Width              int
	Height             int
	EstimatedSizeBytes int64
	Provenance         []string
}

type MediaTrack struct {
	ID         string
	ItemID     string
	Kind       TrackKind
	Language   string
	Codec      string
	Provenance []string
}

type MediaRendition struct {
	ID         string
	ItemID     string
	Group      string
	TrackIDs   []string
	Provenance []string
}

type MediaManifest struct {
	ID           string
	ItemID       string
	SourceID     string
	CanonicalURL string
	Provenance   []string
}

type FragmentSet struct {
	ID         string
	ItemID     string
	Key        string
	SourceID   string
	Provenance []string
}

func NewMediaGraph(limits MediaGraphLimits) *MediaGraph {
	limits = normalizeGraphLimits(limits)
	return &MediaGraph{
		limits:       limits,
		Items:        map[string]*MediaItem{},
		Sources:      map[string]*MediaSource{},
		Variants:     map[string]*MediaVariant{},
		Tracks:       map[string]*MediaTrack{},
		Renditions:   map[string]*MediaRendition{},
		Manifests:    map[string]*MediaManifest{},
		FragmentSets: map[string]*FragmentSet{},
		seenEvidence: map[string]bool{},
	}
}

func (g *MediaGraph) Ingest(obs MediaObservation) (string, error) {
	if g == nil {
		return "", fmt.Errorf("%w: nil graph", ErrInvalidMediaResource)
	}
	if obs.Resource.ResourceID == "" {
		return "", fmt.Errorf("%w: resource identity required", ErrInvalidMediaResource)
	}
	evidence := obs.Envelope.LogicalObservationKey()
	if evidence == "" || evidence == digestID("capobs", "") {
		evidence = obs.Envelope.ReplayEvidenceKey()
	}
	itemID := obs.logicalItemID()
	item := g.Items[itemID]
	if item == nil {
		if len(g.Items) >= g.limits.MaxItems {
			return "", fmt.Errorf("%w: items", ErrMediaGraphLimit)
		}
		item = &MediaItem{ID: itemID, Title: strings.TrimSpace(obs.Title), Protection: normalizeProtection(obs.Protection)}
		g.Items[itemID] = item
	}
	item.Provenance = addUnique(item.Provenance, evidence)
	if item.Title == "" && strings.TrimSpace(obs.Title) != "" {
		item.Title = strings.TrimSpace(obs.Title)
	}
	item.Protection = mergeProtection(item.Protection, obs.Protection)

	sourceID := obs.Resource.ResourceID
	source := g.Sources[sourceID]
	if source == nil {
		if len(g.Sources) >= g.limits.MaxSources {
			return "", fmt.Errorf("%w: sources", ErrMediaGraphLimit)
		}
		source = &MediaSource{ID: sourceID, ResourceID: obs.Resource.ResourceID, TransportURL: obs.Resource.TransportURL, CanonicalURL: obs.Resource.CanonicalURL, Origin: obs.Resource.Origin, CredentialScope: obs.Resource.CredentialScope}
		g.Sources[sourceID] = source
	}
	source.Provenance = addUnique(source.Provenance, evidence)
	item.SourceIDs = addUnique(item.SourceIDs, sourceID)

	variantID := digestID("variant", strings.Join([]string{itemID, sourceID, obs.Container, obs.Codec, fmt.Sprint(obs.Bitrate), fmt.Sprint(obs.Width), fmt.Sprint(obs.Height)}, "\x00"))
	if _, ok := g.Variants[variantID]; !ok {
		if len(g.Variants) >= g.limits.MaxVariants {
			return "", fmt.Errorf("%w: variants", ErrMediaGraphLimit)
		}
		g.Variants[variantID] = &MediaVariant{ID: variantID, ItemID: itemID, SourceID: sourceID, Container: obs.Container, Codec: obs.Codec, Bitrate: obs.Bitrate, Width: obs.Width, Height: obs.Height, EstimatedSizeBytes: obs.EstimatedSizeBytes}
	}
	if g.Variants[variantID].EstimatedSizeBytes == 0 && obs.EstimatedSizeBytes > 0 {
		g.Variants[variantID].EstimatedSizeBytes = obs.EstimatedSizeBytes
	}
	g.Variants[variantID].Provenance = addUnique(g.Variants[variantID].Provenance, evidence)
	item.VariantIDs = addUnique(item.VariantIDs, variantID)

	if obs.TrackKind != "" || obs.Language != "" {
		trackID := digestID("track", strings.Join([]string{itemID, string(obs.TrackKind), obs.Language, obs.Codec}, "\x00"))
		if _, ok := g.Tracks[trackID]; !ok {
			if len(g.Tracks) >= g.limits.MaxTracks {
				return "", fmt.Errorf("%w: tracks", ErrMediaGraphLimit)
			}
			g.Tracks[trackID] = &MediaTrack{ID: trackID, ItemID: itemID, Kind: normalizeTrackKind(obs.TrackKind), Language: strings.ToLower(strings.TrimSpace(obs.Language)), Codec: strings.TrimSpace(obs.Codec)}
		}
		g.Tracks[trackID].Provenance = addUnique(g.Tracks[trackID].Provenance, evidence)
		item.TrackIDs = addUnique(item.TrackIDs, trackID)
		if obs.RenditionGroup != "" {
			rID := digestID("rendition", itemID+"\x00"+obs.RenditionGroup)
			if _, ok := g.Renditions[rID]; !ok {
				if len(g.Renditions) >= g.limits.MaxRenditions {
					return "", fmt.Errorf("%w: renditions", ErrMediaGraphLimit)
				}
				g.Renditions[rID] = &MediaRendition{ID: rID, ItemID: itemID, Group: obs.RenditionGroup}
			}
			g.Renditions[rID].TrackIDs = addUnique(g.Renditions[rID].TrackIDs, trackID)
			g.Renditions[rID].Provenance = addUnique(g.Renditions[rID].Provenance, evidence)
			item.RenditionIDs = addUnique(item.RenditionIDs, rID)
		}
	}
	if obs.ManifestURL != "" {
		manifestRes, err := NewMediaResource(MediaResourceInput{TransportURL: obs.ManifestURL, CredentialScope: obs.Resource.CredentialScope, SignedURLPolicy: obs.Resource.SignedURLPolicy})
		if err != nil {
			return "", err
		}
		mID := digestID("manifest", manifestRes.CanonicalURL)
		if _, ok := g.Manifests[mID]; !ok {
			g.Manifests[mID] = &MediaManifest{ID: mID, ItemID: itemID, SourceID: sourceID, CanonicalURL: manifestRes.CanonicalURL}
		}
		g.Manifests[mID].Provenance = addUnique(g.Manifests[mID].Provenance, evidence)
		item.ManifestIDs = addUnique(item.ManifestIDs, mID)
	}
	if obs.FragmentSetKey != "" {
		fID := digestID("fragmentset", itemID+"\x00"+obs.FragmentSetKey)
		if _, ok := g.FragmentSets[fID]; !ok {
			if len(g.FragmentSets) >= g.limits.MaxFragmentSets {
				return "", fmt.Errorf("%w: fragment_sets", ErrMediaGraphLimit)
			}
			g.FragmentSets[fID] = &FragmentSet{ID: fID, ItemID: itemID, Key: obs.FragmentSetKey, SourceID: sourceID}
		}
		g.FragmentSets[fID].Provenance = addUnique(g.FragmentSets[fID].Provenance, evidence)
		item.FragmentSetIDs = addUnique(item.FragmentSetIDs, fID)
	}
	g.sortAll()
	g.seenEvidence[evidence] = true
	return itemID, nil
}

func (obs MediaObservation) logicalItemID() string {
	material := obs.Resource.CanonicalURL
	if obs.ManifestURL != "" {
		if manifest, err := NewMediaResource(MediaResourceInput{TransportURL: obs.ManifestURL, SignedURLPolicy: obs.Resource.SignedURLPolicy}); err == nil {
			material = manifest.CanonicalURL
		}
	}
	if obs.Title != "" {
		material = strings.TrimSpace(obs.Title) + "\x00" + material
	}
	return digestID("mediaitem", material)
}

func (g *MediaGraph) Snapshot() GraphSnapshot {
	g.sortAll()
	return GraphSnapshot{
		ItemIDs: keysOf(g.Items), SourceIDs: keysOf(g.Sources), VariantIDs: keysOf(g.Variants), TrackIDs: keysOf(g.Tracks), RenditionIDs: keysOf(g.Renditions), ManifestIDs: keysOf(g.Manifests), FragmentSetIDs: keysOf(g.FragmentSets),
	}
}

type GraphSnapshot struct{ ItemIDs, SourceIDs, VariantIDs, TrackIDs, RenditionIDs, ManifestIDs, FragmentSetIDs []string }

func normalizeGraphLimits(l MediaGraphLimits) MediaGraphLimits {
	if l.MaxItems <= 0 {
		l.MaxItems = DefaultMaxMediaGraphItems
	}
	if l.MaxSources <= 0 {
		l.MaxSources = DefaultMaxMediaGraphSources
	}
	if l.MaxVariants <= 0 {
		l.MaxVariants = DefaultMaxMediaGraphVariants
	}
	if l.MaxTracks <= 0 {
		l.MaxTracks = DefaultMaxMediaGraphTracks
	}
	if l.MaxRenditions <= 0 {
		l.MaxRenditions = DefaultMaxMediaGraphRenditions
	}
	if l.MaxFragmentSets <= 0 {
		l.MaxFragmentSets = DefaultMaxMediaGraphFragments
	}
	return l
}

func normalizeTrackKind(k TrackKind) TrackKind {
	switch k {
	case TrackAudio, TrackSubtitle, TrackVideo:
		return k
	default:
		return TrackVideo
	}
}
func normalizeProtection(p ProtectionClass) ProtectionClass {
	switch p {
	case ProtectionClear, ProtectionDRM, ProtectionKeyed:
		return p
	default:
		return ProtectionUnknown
	}
}
func mergeProtection(a ProtectionClass, b ProtectionClass) ProtectionClass {
	b = normalizeProtection(b)
	if b == ProtectionDRM {
		return ProtectionDRM
	}
	if b == ProtectionKeyed && a != ProtectionDRM {
		return ProtectionKeyed
	}
	if a == "" || a == ProtectionUnknown {
		return b
	}
	return a
}

func addUnique(list []string, v string) []string {
	if v == "" {
		return list
	}
	for _, x := range list {
		if x == v {
			return list
		}
	}
	list = append(list, v)
	sort.Strings(list)
	return list
}

func (g *MediaGraph) sortAll() {
	for _, item := range g.Items {
		sort.Strings(item.SourceIDs)
		sort.Strings(item.VariantIDs)
		sort.Strings(item.TrackIDs)
		sort.Strings(item.RenditionIDs)
		sort.Strings(item.ManifestIDs)
		sort.Strings(item.FragmentSetIDs)
		sort.Strings(item.Provenance)
	}
	for _, s := range g.Sources {
		sort.Strings(s.Provenance)
	}
	for _, v := range g.Variants {
		sort.Strings(v.Provenance)
	}
	for _, t := range g.Tracks {
		sort.Strings(t.Provenance)
	}
	for _, r := range g.Renditions {
		sort.Strings(r.TrackIDs)
		sort.Strings(r.Provenance)
	}
	for _, m := range g.Manifests {
		sort.Strings(m.Provenance)
	}
	for _, f := range g.FragmentSets {
		sort.Strings(f.Provenance)
	}
}

func keysOf[T any](m map[string]T) []string {
	out := make([]string, 0, len(m))
	for k := range m {
		out = append(out, k)
	}
	sort.Strings(out)
	return out
}
