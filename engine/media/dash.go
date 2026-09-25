package media

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/xml"
	"errors"
	"fmt"
	"net/url"
	"strconv"
	"strings"
	"time"
)

var (
	ErrInvalidDASHManifest       = errors.New("invalid dash manifest")
	ErrDASHUnsupportedProtection = errors.New("dash protection unsupported")
	ErrDASHFetchFailed           = errors.New("dash fetch failed")
	ErrDASHExecutionStopped      = errors.New("dash execution stopped")
	ErrDASHExecutionInvalidPlan  = errors.New("invalid dash execution plan")
)

type DASHManifestType string

const (
	DASHStatic  DASHManifestType = "static"
	DASHDynamic DASHManifestType = "dynamic"
)

type DASHProtectionKind string

const (
	DASHProtectionClear     DASHProtectionKind = "clear"
	DASHProtectionWidevine  DASHProtectionKind = "widevine"
	DASHProtectionPlayReady DASHProtectionKind = "playready"
	DASHProtectionUnknown   DASHProtectionKind = "unknown"
)

type DASHManifest struct {
	Type                      DASHManifestType `json:"type"`
	BaseURL                   string           `json:"base_url"`
	MinUpdatePeriod           time.Duration    `json:"min_update_period"`
	MediaPresentationDuration time.Duration    `json:"media_presentation_duration"`
	Periods                   []DASHPeriod     `json:"periods"`
}

type DASHPeriod struct {
	ID             string              `json:"id"`
	Start          time.Duration       `json:"start"`
	Duration       time.Duration       `json:"duration"`
	BaseURL        string              `json:"base_url"`
	AdaptationSets []DASHAdaptationSet `json:"adaptation_sets"`
}

type DASHAdaptationSet struct {
	ID              string               `json:"id"`
	ContentType     string               `json:"content_type"`
	Language        string               `json:"language,omitempty"`
	Roles           []string             `json:"roles,omitempty"`
	BaseURL         string               `json:"base_url"`
	Protection      DASHProtectionKind   `json:"protection"`
	SegmentTemplate *DASHSegmentTemplate `json:"segment_template,omitempty"`
	SegmentList     *DASHSegmentList     `json:"segment_list,omitempty"`
	Representations []DASHRepresentation `json:"representations"`
}

type DASHRepresentation struct {
	ID                string               `json:"id"`
	Bandwidth         int                  `json:"bandwidth,omitempty"`
	MimeType          string               `json:"mime_type,omitempty"`
	Codecs            string               `json:"codecs,omitempty"`
	Width             int                  `json:"width,omitempty"`
	Height            int                  `json:"height,omitempty"`
	AudioSamplingRate int                  `json:"audio_sampling_rate,omitempty"`
	BaseURL           string               `json:"base_url"`
	Protection        DASHProtectionKind   `json:"protection"`
	SegmentTemplate   *DASHSegmentTemplate `json:"segment_template,omitempty"`
	SegmentList       *DASHSegmentList     `json:"segment_list,omitempty"`
}

type DASHSegmentTemplate struct {
	Timescale      int64               `json:"timescale"`
	Duration       int64               `json:"duration,omitempty"`
	StartNumber    int64               `json:"start_number"`
	Media          string              `json:"media,omitempty"`
	Initialization string              `json:"initialization,omitempty"`
	Timeline       []DASHTimelineEntry `json:"timeline,omitempty"`
}

type DASHTimelineEntry struct {
	T *int64 `json:"t,omitempty"`
	D int64  `json:"d"`
	R int64  `json:"r,omitempty"`
}

type DASHSegmentList struct {
	Timescale      int64            `json:"timescale"`
	Duration       int64            `json:"duration,omitempty"`
	Initialization string           `json:"initialization,omitempty"`
	SegmentURLs    []DASHSegmentURL `json:"segment_urls"`
}

type DASHSegmentURL struct {
	Media      string     `json:"media"`
	MediaRange *ByteRange `json:"media_range,omitempty"`
}

type DASHFragment struct {
	PeriodID          string             `json:"period_id"`
	AdaptationID      string             `json:"adaptation_id"`
	RepresentationID  string             `json:"representation_id"`
	Role              string             `json:"role"`
	Number            int64              `json:"number"`
	Time              int64              `json:"time"`
	Duration          int64              `json:"duration"`
	URL               string             `json:"url"`
	InitializationURL string             `json:"initialization_url,omitempty"`
	Range             *ByteRange         `json:"range,omitempty"`
	Protection        DASHProtectionKind `json:"protection"`
	TimelineKey       string             `json:"timeline_key"`
	Identity          FragmentIdentity   `json:"identity"`
}

type dashMPD struct {
	XMLName                   xml.Name     `xml:"MPD"`
	Type                      string       `xml:"type,attr"`
	MinUpdatePeriod           string       `xml:"minimumUpdatePeriod,attr"`
	MediaPresentationDuration string       `xml:"mediaPresentationDuration,attr"`
	BaseURLs                  []string     `xml:"BaseURL"`
	Periods                   []dashPeriod `xml:"Period"`
}

type dashPeriod struct {
	ID             string              `xml:"id,attr"`
	Start          string              `xml:"start,attr"`
	Duration       string              `xml:"duration,attr"`
	BaseURLs       []string            `xml:"BaseURL"`
	AdaptationSets []dashAdaptationSet `xml:"AdaptationSet"`
}

type dashAdaptationSet struct {
	ID                 string                  `xml:"id,attr"`
	ContentType        string                  `xml:"contentType,attr"`
	MimeType           string                  `xml:"mimeType,attr"`
	Lang               string                  `xml:"lang,attr"`
	BaseURLs           []string                `xml:"BaseURL"`
	Roles              []dashRole              `xml:"Role"`
	ContentProtections []dashContentProtection `xml:"ContentProtection"`
	SegmentTemplate    *dashSegmentTemplate    `xml:"SegmentTemplate"`
	SegmentList        *dashSegmentList        `xml:"SegmentList"`
	Representations    []dashRepresentation    `xml:"Representation"`
}

type dashRepresentation struct {
	ID                 string                  `xml:"id,attr"`
	Bandwidth          string                  `xml:"bandwidth,attr"`
	MimeType           string                  `xml:"mimeType,attr"`
	Codecs             string                  `xml:"codecs,attr"`
	Width              string                  `xml:"width,attr"`
	Height             string                  `xml:"height,attr"`
	AudioSamplingRate  string                  `xml:"audioSamplingRate,attr"`
	BaseURLs           []string                `xml:"BaseURL"`
	ContentProtections []dashContentProtection `xml:"ContentProtection"`
	SegmentTemplate    *dashSegmentTemplate    `xml:"SegmentTemplate"`
	SegmentList        *dashSegmentList        `xml:"SegmentList"`
}

type dashRole struct {
	Value string `xml:"value,attr"`
}
type dashContentProtection struct {
	SchemeIDURI string `xml:"schemeIdUri,attr"`
}

type dashSegmentTemplate struct {
	Timescale      string               `xml:"timescale,attr"`
	Duration       string               `xml:"duration,attr"`
	StartNumber    string               `xml:"startNumber,attr"`
	Media          string               `xml:"media,attr"`
	Initialization string               `xml:"initialization,attr"`
	Timeline       *dashSegmentTimeline `xml:"SegmentTimeline"`
}
type dashSegmentTimeline struct {
	S []dashS `xml:"S"`
}
type dashS struct {
	T string `xml:"t,attr"`
	D string `xml:"d,attr"`
	R string `xml:"r,attr"`
}

type dashSegmentList struct {
	Timescale      string              `xml:"timescale,attr"`
	Duration       string              `xml:"duration,attr"`
	Initialization *dashInitialization `xml:"Initialization"`
	SegmentURLs    []dashSegmentURL    `xml:"SegmentURL"`
}
type dashInitialization struct {
	SourceURL string `xml:"sourceURL,attr"`
	Range     string `xml:"range,attr"`
}
type dashSegmentURL struct {
	Media      string `xml:"media,attr"`
	MediaRange string `xml:"mediaRange,attr"`
}

func ParseDASHManifest(baseURL string, body string) (DASHManifest, error) {
	base, err := normalizeHTTPURL(baseURL, "dash.base_url")
	if err != nil {
		return DASHManifest{}, fmt.Errorf("%w: %v", ErrInvalidDASHManifest, err)
	}
	var raw dashMPD
	dec := xml.NewDecoder(strings.NewReader(body))
	dec.Strict = true
	if err := dec.Decode(&raw); err != nil {
		return DASHManifest{}, fmt.Errorf("%w: %v", ErrInvalidDASHManifest, err)
	}
	if raw.XMLName.Local != "MPD" {
		return DASHManifest{}, fmt.Errorf("%w: missing MPD", ErrInvalidDASHManifest)
	}
	manifestBase, err := resolveDASHBase(base, raw.BaseURLs)
	if err != nil {
		return DASHManifest{}, err
	}
	m := DASHManifest{Type: normalizeDASHType(raw.Type), BaseURL: manifestBase, MinUpdatePeriod: parseISODuration(raw.MinUpdatePeriod), MediaPresentationDuration: parseISODuration(raw.MediaPresentationDuration)}
	for pi, rp := range raw.Periods {
		periodID := strings.TrimSpace(rp.ID)
		if periodID == "" {
			periodID = fmt.Sprintf("period-%d", pi+1)
		}
		pbase, err := resolveDASHBase(manifestBase, rp.BaseURLs)
		if err != nil {
			return DASHManifest{}, err
		}
		period := DASHPeriod{ID: periodID, Start: parseISODuration(rp.Start), Duration: parseISODuration(rp.Duration), BaseURL: pbase}
		for ai, ra := range rp.AdaptationSets {
			abase, err := resolveDASHBase(pbase, ra.BaseURLs)
			if err != nil {
				return DASHManifest{}, err
			}
			aid := strings.TrimSpace(ra.ID)
			if aid == "" {
				aid = fmt.Sprintf("adaptation-%d", ai+1)
			}
			content := strings.TrimSpace(ra.ContentType)
			if content == "" {
				content = inferDASHContentType(ra.MimeType)
			}
			roles := []string{}
			for _, r := range ra.Roles {
				if v := strings.ToLower(strings.TrimSpace(r.Value)); v != "" {
					roles = append(roles, v)
				}
			}
			aset := DASHAdaptationSet{ID: aid, ContentType: content, Language: strings.TrimSpace(ra.Lang), Roles: roles, BaseURL: abase, Protection: classifyDASHProtection(ra.ContentProtections), SegmentTemplate: convertDASHTemplate(ra.SegmentTemplate), SegmentList: convertDASHSegmentList(ra.SegmentList)}
			for ri, rr := range ra.Representations {
				rbase, err := resolveDASHBase(abase, rr.BaseURLs)
				if err != nil {
					return DASHManifest{}, err
				}
				rid := strings.TrimSpace(rr.ID)
				if rid == "" {
					rid = fmt.Sprintf("representation-%d", ri+1)
				}
				rep := DASHRepresentation{ID: rid, Bandwidth: atoi(rr.Bandwidth), MimeType: firstNonEmpty(rr.MimeType, ra.MimeType), Codecs: strings.TrimSpace(rr.Codecs), Width: atoi(rr.Width), Height: atoi(rr.Height), AudioSamplingRate: atoi(rr.AudioSamplingRate), BaseURL: rbase, Protection: mergeDASHProtection(aset.Protection, classifyDASHProtection(rr.ContentProtections)), SegmentTemplate: convertDASHTemplate(rr.SegmentTemplate), SegmentList: convertDASHSegmentList(rr.SegmentList)}
				aset.Representations = append(aset.Representations, rep)
			}
			period.AdaptationSets = append(period.AdaptationSets, aset)
		}
		m.Periods = append(m.Periods, period)
	}
	return m, nil
}

func ExpandDASHFragments(m DASHManifest) ([]DASHFragment, error) {
	out := []DASHFragment{}
	for _, p := range m.Periods {
		for _, a := range p.AdaptationSets {
			for _, r := range a.Representations {
				tpl := r.SegmentTemplate
				if tpl == nil {
					tpl = a.SegmentTemplate
				}
				list := r.SegmentList
				if list == nil {
					list = a.SegmentList
				}
				prot := mergeDASHProtection(a.Protection, r.Protection)
				if tpl != nil {
					frags, err := expandDASHTemplate(m, p, a, r, *tpl, prot)
					if err != nil {
						return nil, err
					}
					out = append(out, frags...)
				}
				if list != nil {
					frags, err := expandDASHList(p, a, r, *list, prot)
					if err != nil {
						return nil, err
					}
					out = append(out, frags...)
				}
			}
		}
	}
	return out, nil
}

func expandDASHTemplate(m DASHManifest, p DASHPeriod, a DASHAdaptationSet, r DASHRepresentation, tpl DASHSegmentTemplate, prot DASHProtectionKind) ([]DASHFragment, error) {
	timescale := tpl.Timescale
	if timescale <= 0 {
		timescale = 1
	}
	start := tpl.StartNumber
	if start <= 0 {
		start = 1
	}
	initURL := ""
	if tpl.Initialization != "" {
		initURL = resolveDASHURL(r.BaseURL, replaceDASHTokens(tpl.Initialization, r, start, 0, 0, 0))
	}
	entries := expandDASHTimeline(tpl.Timeline, tpl.Duration, timescale, durationUnits(p.Duration, timescale), durationUnits(m.MediaPresentationDuration, timescale))
	out := []DASHFragment{}
	for i, ent := range entries {
		num := start + int64(i)
		mediaPath := replaceDASHTokens(tpl.Media, r, num, ent.Time, ent.Duration, tpl.BandwidthOrRepresentationBandwidth(r))
		if mediaPath == "" {
			mediaPath = r.BaseURL
		}
		u := resolveDASHURL(r.BaseURL, mediaPath)
		f, err := newDASHFragment(p, a, r, num, ent.Time, ent.Duration, u, initURL, nil, prot)
		if err != nil {
			return nil, err
		}
		out = append(out, f)
	}
	return out, nil
}

func (t DASHSegmentTemplate) BandwidthOrRepresentationBandwidth(r DASHRepresentation) int64 {
	return int64(r.Bandwidth)
}

type dashExpandedTime struct{ Time, Duration int64 }

func expandDASHTimeline(entries []DASHTimelineEntry, duration, timescale, periodUnits, mpdUnits int64) []dashExpandedTime {
	out := []dashExpandedTime{}
	if len(entries) == 0 {
		d := duration
		if d <= 0 {
			return out
		}
		limit := periodUnits
		if limit <= 0 {
			limit = mpdUnits
		}
		if limit <= 0 {
			limit = d * 1
		}
		max := int(limit / d)
		if max <= 0 {
			max = 1
		}
		if max > 64 {
			max = 64
		}
		for i := 0; i < max; i++ {
			out = append(out, dashExpandedTime{Time: int64(i) * d, Duration: d})
		}
		return out
	}
	current := int64(0)
	for i, e := range entries {
		if e.D <= 0 {
			continue
		}
		if e.T != nil {
			current = *e.T
		}
		repeat := e.R
		count := int64(1)
		if repeat >= 0 {
			count = repeat + 1
		} else {
			nextTime := int64(0)
			for j := i + 1; j < len(entries); j++ {
				if entries[j].T != nil {
					nextTime = *entries[j].T
					break
				}
			}
			if nextTime > current {
				count = (nextTime - current) / e.D
			} else {
				limit := periodUnits
				if limit <= 0 {
					limit = mpdUnits
				}
				if limit > current {
					count = (limit - current) / e.D
				}
				if count <= 0 {
					count = 1
				}
				if count > 64 {
					count = 64
				}
			}
		}
		if count > 256 {
			count = 256
		}
		for n := int64(0); n < count; n++ {
			out = append(out, dashExpandedTime{Time: current, Duration: e.D})
			current += e.D
		}
	}
	return out
}

func expandDASHList(p DASHPeriod, a DASHAdaptationSet, r DASHRepresentation, list DASHSegmentList, prot DASHProtectionKind) ([]DASHFragment, error) {
	out := []DASHFragment{}
	initURL := ""
	if list.Initialization != "" {
		initURL = resolveDASHURL(r.BaseURL, list.Initialization)
	}
	dur := list.Duration
	for i, s := range list.SegmentURLs {
		u := resolveDASHURL(r.BaseURL, s.Media)
		f, err := newDASHFragment(p, a, r, int64(i+1), int64(i)*dur, dur, u, initURL, cloneByteRange(s.MediaRange), prot)
		if err != nil {
			return nil, err
		}
		out = append(out, f)
	}
	return out, nil
}

func newDASHFragment(p DASHPeriod, a DASHAdaptationSet, r DASHRepresentation, number, start, duration int64, uri, initURI string, rng *ByteRange, prot DASHProtectionKind) (DASHFragment, error) {
	res, err := NewMediaResource(MediaResourceInput{TransportURL: uri})
	if err != nil {
		return DASHFragment{}, err
	}
	role := normalizeDASHRole(a.ContentType, a.Roles)
	key := fmt.Sprintf("dash:%s:%s:%s:%d:%d", p.ID, a.ID, r.ID, start, number)
	enc := ""
	if prot != "" && prot != DASHProtectionClear {
		enc = string(prot)
	}
	id := FragmentIdentity{Protocol: FragmentProtocolDASH, TimelineKey: key, ResourceID: res.ResourceID, Range: cloneByteRange(rng), EncryptionID: enc}
	return DASHFragment{PeriodID: p.ID, AdaptationID: a.ID, RepresentationID: r.ID, Role: role, Number: number, Time: start, Duration: duration, URL: uri, InitializationURL: initURI, Range: rng, Protection: prot, TimelineKey: key, Identity: id}, nil
}

func ReconcileDASHTimeline(previous []FragmentRecord, manifest DASHManifest) ([]DASHFragment, int, error) {
	fragments, err := ExpandDASHFragments(manifest)
	if err != nil {
		return nil, 0, err
	}
	prev := map[string]FragmentRecord{}
	for _, r := range previous {
		if id, err := FragmentID(r.Identity); err == nil {
			prev[id] = r
		}
	}
	filtered := []DASHFragment{}
	skipped := 0
	for _, f := range fragments {
		id, _ := FragmentID(f.Identity)
		if rec, ok := prev[id]; ok && (rec.State == FragmentCommitted || rec.State == FragmentHistorical) {
			skipped++
			continue
		}
		filtered = append(filtered, f)
	}
	return filtered, skipped, nil
}

type DASHFetchKind string

const (
	DASHFetchInit     DASHFetchKind = "init"
	DASHFetchMedia    DASHFetchKind = "media"
	DASHFetchManifest DASHFetchKind = "manifest"
)

type DASHFetchRequest struct {
	URI           string
	Kind          DASHFetchKind
	Range         *ByteRange
	CredentialRef string
}
type DASHFetchResponse struct{ Bytes []byte }
type DASHFetcher interface {
	FetchDASH(context.Context, DASHFetchRequest) (DASHFetchResponse, error)
}

type DASHExecutionPlan struct {
	BaseURL            string
	Manifests          []string
	AttemptGeneration  int64
	MaxPolls           int
	StopAfterFragments int
	CredentialRef      string
	RepresentationIDs  []string
}
type DASHFragmentOutcome struct {
	TimelineKey string `json:"timeline_key"`
	URL         string `json:"url"`
	Role        string `json:"role"`
	Status      string `json:"status"`
	ArtifactRef string `json:"artifact_ref,omitempty"`
	Hash        string `json:"hash,omitempty"`
	Bytes       int    `json:"bytes,omitempty"`
	Reason      string `json:"reason,omitempty"`
}
type DASHTrackArtifact struct {
	Role             string   `json:"role"`
	RepresentationID string   `json:"representation_id"`
	ArtifactRefs     []string `json:"artifact_refs"`
}
type DASHExecutionResult struct {
	Status           string                `json:"status"`
	Polls            int                   `json:"polls"`
	Completed        int                   `json:"completed"`
	Skipped          int                   `json:"skipped"`
	Unsupported      int                   `json:"unsupported"`
	FetchFailures    int                   `json:"fetch_failures"`
	LiveBoundReached bool                  `json:"live_bound_reached"`
	Outcomes         []DASHFragmentOutcome `json:"outcomes"`
	TrackArtifacts   []DASHTrackArtifact   `json:"track_artifacts"`
}

type DASHExecutor struct {
	fetcher DASHFetcher
	ledger  *FragmentLedger
}

func NewDASHExecutor(fetcher DASHFetcher, ledger *FragmentLedger) (*DASHExecutor, error) {
	if fetcher == nil {
		return nil, fmt.Errorf("%w: nil fetcher", ErrDASHExecutionInvalidPlan)
	}
	if ledger == nil {
		var err error
		ledger, err = NewFragmentLedger()
		if err != nil {
			return nil, err
		}
	}
	return &DASHExecutor{fetcher: fetcher, ledger: ledger}, nil
}
func (e *DASHExecutor) Ledger() *FragmentLedger { return e.ledger }

func (e *DASHExecutor) Execute(ctx context.Context, plan DASHExecutionPlan) (DASHExecutionResult, error) {
	if e == nil || e.fetcher == nil || e.ledger == nil {
		return DASHExecutionResult{}, fmt.Errorf("%w: nil executor", ErrDASHExecutionInvalidPlan)
	}
	if strings.TrimSpace(plan.BaseURL) == "" || len(plan.Manifests) == 0 {
		return DASHExecutionResult{}, fmt.Errorf("%w: base url and manifests required", ErrDASHExecutionInvalidPlan)
	}
	gen := plan.AttemptGeneration
	if gen <= 0 {
		gen = 1
	}
	max := plan.MaxPolls
	if max <= 0 || max > len(plan.Manifests) {
		max = len(plan.Manifests)
	}
	selected := map[string]bool{}
	for _, id := range plan.RepresentationIDs {
		if s := strings.TrimSpace(id); s != "" {
			selected[s] = true
		}
	}
	result := DASHExecutionResult{Status: "live_wait"}
	trackMap := map[string]*DASHTrackArtifact{}
	completedNow := 0
	for i := 0; i < max; i++ {
		if err := ctx.Err(); err != nil {
			result.Status = "stopped"
			return result, err
		}
		m, err := ParseDASHManifest(plan.BaseURL, plan.Manifests[i])
		if err != nil {
			result.Status = "failed"
			return result, err
		}
		result.Polls++
		pending, skipped, err := ReconcileDASHTimeline(e.ledger.Snapshot(), m)
		if err != nil {
			result.Status = "failed"
			return result, err
		}
		result.Skipped += skipped
		for _, f := range pending {
			if len(selected) > 0 && !selected[f.RepresentationID] {
				continue
			}
			out := e.executeDASHFragment(ctx, f, gen, plan.CredentialRef)
			result.Outcomes = append(result.Outcomes, out)
			switch out.Status {
			case "committed":
				result.Completed++
				completedNow++
				key := out.Role + "|" + f.RepresentationID
				art := trackMap[key]
				if art == nil {
					trackMap[key] = &DASHTrackArtifact{Role: out.Role, RepresentationID: f.RepresentationID}
					art = trackMap[key]
				}
				art.ArtifactRefs = append(art.ArtifactRefs, out.ArtifactRef)
			case "unsupported":
				result.Unsupported++
			case "fetch_failed":
				result.FetchFailures++
			}
			if plan.StopAfterFragments > 0 && completedNow >= plan.StopAfterFragments {
				result.Status = "stopped"
				result.LiveBoundReached = true
				for _, v := range trackMap {
					result.TrackArtifacts = append(result.TrackArtifacts, *v)
				}
				return result, ErrDASHExecutionStopped
			}
		}
		if m.Type == DASHStatic {
			result.Status = "completed"
		} else {
			result.Status = "live_wait"
		}
	}
	if result.Status == "live_wait" {
		result.LiveBoundReached = true
	}
	for _, v := range trackMap {
		result.TrackArtifacts = append(result.TrackArtifacts, *v)
	}
	return result, nil
}

func (e *DASHExecutor) executeDASHFragment(ctx context.Context, f DASHFragment, gen int64, credential string) DASHFragmentOutcome {
	out := DASHFragmentOutcome{TimelineKey: f.TimelineKey, URL: f.URL, Role: f.Role}
	if f.Protection != "" && f.Protection != DASHProtectionClear {
		out.Status = "unsupported"
		out.Reason = string(f.Protection)
		_, _ = e.ledger.MarkCorrupt(f.Identity, gen, ErrDASHUnsupportedProtection.Error()+":"+string(f.Protection))
		return out
	}
	if f.InitializationURL != "" {
		if _, err := e.fetcher.FetchDASH(ctx, DASHFetchRequest{URI: f.InitializationURL, Kind: DASHFetchInit, CredentialRef: credential}); err != nil {
			out.Status = "fetch_failed"
			out.Reason = "init"
			_, _ = e.ledger.MarkCorrupt(f.Identity, gen, "dash_init_fetch_failed")
			return out
		}
	}
	body, err := e.fetcher.FetchDASH(ctx, DASHFetchRequest{URI: f.URL, Kind: DASHFetchMedia, Range: cloneByteRange(f.Range), CredentialRef: credential})
	if err != nil {
		out.Status = "fetch_failed"
		out.Reason = "media"
		_, _ = e.ledger.MarkCorrupt(f.Identity, gen, ErrDASHFetchFailed.Error())
		return out
	}
	sum := sha256.Sum256(body.Bytes)
	hash := "sha256:" + hex.EncodeToString(sum[:])
	art := artifactRefForFragment(f.Identity)
	rec := FragmentRecord{Identity: f.Identity, Role: f.Role, State: FragmentCommitted, ExpectedLength: int64(len(body.Bytes)), Hash: hash, AttemptGeneration: gen, LocalArtifactRef: art, DurableBytesWritten: true}
	if _, err := e.ledger.Commit(rec); err != nil {
		out.Status = "fetch_failed"
		out.Reason = err.Error()
		return out
	}
	out.Status = "committed"
	out.ArtifactRef = art
	out.Hash = hash
	out.Bytes = len(body.Bytes)
	return out
}

func convertDASHTemplate(raw *dashSegmentTemplate) *DASHSegmentTemplate {
	if raw == nil {
		return nil
	}
	t := &DASHSegmentTemplate{Timescale: i64(raw.Timescale), Duration: i64(raw.Duration), StartNumber: i64(raw.StartNumber), Media: strings.TrimSpace(raw.Media), Initialization: strings.TrimSpace(raw.Initialization)}
	if t.Timescale <= 0 {
		t.Timescale = 1
	}
	if t.StartNumber <= 0 {
		t.StartNumber = 1
	}
	if raw.Timeline != nil {
		for _, s := range raw.Timeline.S {
			d := i64(s.D)
			if d <= 0 {
				continue
			}
			e := DASHTimelineEntry{D: d, R: i64Default(s.R, 0)}
			if strings.TrimSpace(s.T) != "" {
				v := i64(s.T)
				e.T = &v
			}
			t.Timeline = append(t.Timeline, e)
		}
	}
	return t
}
func convertDASHSegmentList(raw *dashSegmentList) *DASHSegmentList {
	if raw == nil {
		return nil
	}
	l := &DASHSegmentList{Timescale: i64(raw.Timescale), Duration: i64(raw.Duration)}
	if l.Timescale <= 0 {
		l.Timescale = 1
	}
	if raw.Initialization != nil {
		l.Initialization = strings.TrimSpace(raw.Initialization.SourceURL)
	}
	for _, s := range raw.SegmentURLs {
		l.SegmentURLs = append(l.SegmentURLs, DASHSegmentURL{Media: strings.TrimSpace(s.Media), MediaRange: parseDASHRange(s.MediaRange)})
	}
	return l
}
func parseDASHRange(raw string) *ByteRange {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return nil
	}
	parts := strings.Split(raw, "-")
	if len(parts) != 2 {
		return nil
	}
	start, err1 := strconv.ParseInt(parts[0], 10, 64)
	end, err2 := strconv.ParseInt(parts[1], 10, 64)
	if err1 != nil || err2 != nil || end < start {
		return nil
	}
	return &ByteRange{Offset: start, Length: end - start + 1}
}
func resolveDASHBase(parent string, bases []string) (string, error) {
	base := parent
	for _, b := range bases {
		if strings.TrimSpace(b) == "" {
			continue
		}
		u, err := url.Parse(strings.TrimSpace(b))
		if err != nil {
			return "", fmt.Errorf("%w: base url", ErrInvalidDASHManifest)
		}
		p, _ := url.Parse(base)
		base = p.ResolveReference(u).String()
	}
	return normalizeHTTPURL(base, "dash.base_url")
}
func resolveDASHURL(base, child string) string {
	if strings.TrimSpace(child) == "" {
		return base
	}
	u, err := url.Parse(strings.TrimSpace(child))
	if err != nil {
		return child
	}
	b, _ := url.Parse(base)
	return b.ResolveReference(u).String()
}
func replaceDASHTokens(s string, r DASHRepresentation, number, t, dur, bandwidth int64) string {
	out := strings.TrimSpace(s)
	out = strings.ReplaceAll(out, "$RepresentationID$", r.ID)
	out = strings.ReplaceAll(out, "$Number$", strconv.FormatInt(number, 10))
	out = strings.ReplaceAll(out, "$Time$", strconv.FormatInt(t, 10))
	out = strings.ReplaceAll(out, "$Bandwidth$", strconv.FormatInt(bandwidth, 10))
	out = strings.ReplaceAll(out, "$Duration$", strconv.FormatInt(dur, 10))
	return out
}
func normalizeDASHType(raw string) DASHManifestType {
	if strings.EqualFold(strings.TrimSpace(raw), "dynamic") {
		return DASHDynamic
	}
	return DASHStatic
}
func inferDASHContentType(mime string) string {
	mime = strings.ToLower(strings.TrimSpace(mime))
	switch {
	case strings.HasPrefix(mime, "video/"):
		return "video"
	case strings.HasPrefix(mime, "audio/"):
		return "audio"
	case strings.Contains(mime, "text") || strings.Contains(mime, "vtt"):
		return "subtitle"
	default:
		return "media"
	}
}
func normalizeDASHRole(content string, roles []string) string {
	c := strings.ToLower(strings.TrimSpace(content))
	if c == "text" {
		c = "subtitle"
	}
	if c == "video" || c == "audio" || c == "subtitle" {
		return c
	}
	for _, r := range roles {
		r = strings.ToLower(strings.TrimSpace(r))
		if r == "subtitle" || r == "caption" {
			return "subtitle"
		}
	}
	return "media"
}
func classifyDASHProtection(protections []dashContentProtection) DASHProtectionKind {
	if len(protections) == 0 {
		return DASHProtectionClear
	}
	for _, p := range protections {
		s := strings.ToLower(p.SchemeIDURI)
		switch {
		case strings.Contains(s, "widevine") || strings.Contains(s, "edef8ba9"):
			return DASHProtectionWidevine
		case strings.Contains(s, "playready") || strings.Contains(s, "9a04f079"):
			return DASHProtectionPlayReady
		case strings.TrimSpace(s) != "":
			return DASHProtectionUnknown
		}
	}
	return DASHProtectionClear
}
func mergeDASHProtection(a, b DASHProtectionKind) DASHProtectionKind {
	if b != "" && b != DASHProtectionClear {
		return b
	}
	if a != "" {
		return a
	}
	return DASHProtectionClear
}
func atoi(s string) int  { v, _ := strconv.Atoi(strings.TrimSpace(s)); return v }
func i64(s string) int64 { v, _ := strconv.ParseInt(strings.TrimSpace(s), 10, 64); return v }
func i64Default(s string, d int64) int64 {
	if strings.TrimSpace(s) == "" {
		return d
	}
	return i64(s)
}
func firstNonEmpty(vs ...string) string {
	for _, v := range vs {
		if strings.TrimSpace(v) != "" {
			return strings.TrimSpace(v)
		}
	}
	return ""
}
func durationUnits(d time.Duration, timescale int64) int64 {
	if d <= 0 || timescale <= 0 {
		return 0
	}
	return int64(d.Seconds() * float64(timescale))
}
func parseISODuration(raw string) time.Duration {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return 0
	}
	if !strings.HasPrefix(raw, "P") {
		return 0
	} // Small, deterministic subset enough for DASH MPD media durations.
	s := strings.TrimPrefix(raw, "P")
	s = strings.TrimPrefix(s, "T")
	total := time.Duration(0)
	num := ""
	for _, r := range s {
		switch r {
		case 'H':
			v, _ := strconv.ParseFloat(num, 64)
			total += time.Duration(v * float64(time.Hour))
			num = ""
		case 'M':
			v, _ := strconv.ParseFloat(num, 64)
			total += time.Duration(v * float64(time.Minute))
			num = ""
		case 'S':
			v, _ := strconv.ParseFloat(num, 64)
			total += time.Duration(v * float64(time.Second))
			num = ""
		default:
			num += string(r)
		}
	}
	return total
}
