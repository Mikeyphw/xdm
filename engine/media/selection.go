package media

import (
	"errors"
	"fmt"
	"sort"
	"strings"
)

var (
	ErrMediaSelection         = errors.New("media selection failed")
	ErrMediaSelectionNoVideo  = errors.New("media selection failed: no eligible video")
	ErrMediaSelectionSubtitle = errors.New("media selection failed: required subtitle unavailable")
)

type SubtitlePolicy string

const (
	SubtitleOptional SubtitlePolicy = "optional"
	SubtitleRequired SubtitlePolicy = "required"
	SubtitleDisabled SubtitlePolicy = "disabled"
)

type SelectionChoiceKind string

const (
	SelectionVideo    SelectionChoiceKind = "video"
	SelectionAudio    SelectionChoiceKind = "audio"
	SelectionSubtitle SelectionChoiceKind = "subtitle"
)

type MediaSelectionConstraints struct {
	ItemID                     string
	MaxWidth                   int
	MaxHeight                  int
	PreferredWidth             int
	PreferredHeight            int
	MaxBitrate                 int
	MaxEstimatedSizeBytes      int64
	PreferredCodecs            []string
	PreferredContainers        []string
	PreferredAudioLanguages    []string
	PreferredSubtitleLanguages []string
	SubtitlePolicy             SubtitlePolicy
}

type MediaChoice struct {
	ID                 string              `json:"id"`
	Kind               SelectionChoiceKind `json:"kind"`
	VariantID          string              `json:"variant_id,omitempty"`
	TrackID            string              `json:"track_id,omitempty"`
	Width              int                 `json:"width,omitempty"`
	Height             int                 `json:"height,omitempty"`
	Bitrate            int                 `json:"bitrate,omitempty"`
	EstimatedSizeBytes int64               `json:"estimated_size_bytes,omitempty"`
	Codec              string              `json:"codec,omitempty"`
	Container          string              `json:"container,omitempty"`
	Language           string              `json:"language,omitempty"`
}

type SelectionDecision struct {
	Code      string `json:"code"`
	SubjectID string `json:"subject_id,omitempty"`
}

type MediaSelectionResult struct {
	ItemID           string              `json:"item_id"`
	AvailableChoices []MediaChoice       `json:"available_choices"`
	DefaultVideo     *MediaChoice        `json:"default_video,omitempty"`
	SelectedVideo    *MediaChoice        `json:"selected_video,omitempty"`
	SelectedAudio    *MediaChoice        `json:"selected_audio,omitempty"`
	SelectedSubtitle *MediaChoice        `json:"selected_subtitle,omitempty"`
	Rationale        []SelectionDecision `json:"rationale"`
}

func SelectMedia(g *MediaGraph, constraints MediaSelectionConstraints) (MediaSelectionResult, error) {
	if g == nil {
		return MediaSelectionResult{}, fmt.Errorf("%w: nil graph", ErrMediaSelection)
	}
	itemID, err := resolveSelectionItem(g, strings.TrimSpace(constraints.ItemID))
	if err != nil {
		return MediaSelectionResult{}, err
	}
	result := MediaSelectionResult{ItemID: itemID}
	videos := videoChoices(g, itemID)
	audios := trackChoices(g, itemID, TrackAudio)
	subtitles := trackChoices(g, itemID, TrackSubtitle)
	result.AvailableChoices = append(result.AvailableChoices, videos...)
	result.AvailableChoices = append(result.AvailableChoices, audios...)
	result.AvailableChoices = append(result.AvailableChoices, subtitles...)
	sortChoices(result.AvailableChoices)
	if len(videos) == 0 {
		return result, ErrMediaSelectionNoVideo
	}
	defaults := append([]MediaChoice(nil), videos...)
	sort.SliceStable(defaults, func(i, j int) bool { return defaultVideoLess(defaults[i], defaults[j]) })
	result.DefaultVideo = choicePtr(defaults[0])

	eligible, rejections := eligibleVideoChoices(videos, constraints)
	result.Rationale = append(result.Rationale, rejections...)
	if len(eligible) == 0 {
		return result, ErrMediaSelectionNoVideo
	}
	sort.SliceStable(eligible, func(i, j int) bool { return selectedVideoLess(eligible[i], eligible[j], constraints) })
	result.SelectedVideo = choicePtr(eligible[0])
	result.Rationale = append(result.Rationale, SelectionDecision{Code: "selected_video", SubjectID: eligible[0].ID})

	selectedAudio, audioDecision := chooseLanguageTrack(audios, constraints.PreferredAudioLanguages, true)
	if selectedAudio != nil {
		result.SelectedAudio = selectedAudio
		result.Rationale = append(result.Rationale, audioDecision)
	}

	switch normalizeSubtitlePolicy(constraints.SubtitlePolicy) {
	case SubtitleDisabled:
		result.Rationale = append(result.Rationale, SelectionDecision{Code: "subtitles_disabled"})
	case SubtitleRequired:
		selectedSubtitle, subDecision := chooseLanguageTrack(subtitles, constraints.PreferredSubtitleLanguages, true)
		if selectedSubtitle == nil {
			return result, ErrMediaSelectionSubtitle
		}
		result.SelectedSubtitle = selectedSubtitle
		result.Rationale = append(result.Rationale, subDecision)
	case SubtitleOptional:
		selectedSubtitle, subDecision := chooseLanguageTrack(subtitles, constraints.PreferredSubtitleLanguages, false)
		if selectedSubtitle != nil {
			result.SelectedSubtitle = selectedSubtitle
			result.Rationale = append(result.Rationale, subDecision)
		} else {
			result.Rationale = append(result.Rationale, SelectionDecision{Code: "subtitle_optional_not_selected"})
		}
	}
	return result, nil
}

func resolveSelectionItem(g *MediaGraph, requested string) (string, error) {
	if requested != "" {
		if _, ok := g.Items[requested]; ok {
			return requested, nil
		}
		return "", fmt.Errorf("%w: item not found", ErrMediaSelection)
	}
	ids := keysOf(g.Items)
	if len(ids) == 0 {
		return "", fmt.Errorf("%w: empty graph", ErrMediaSelection)
	}
	if len(ids) > 1 {
		return "", fmt.Errorf("%w: item id required", ErrMediaSelection)
	}
	return ids[0], nil
}

func videoChoices(g *MediaGraph, itemID string) []MediaChoice {
	out := []MediaChoice{}
	for _, id := range keysOf(g.Variants) {
		v := g.Variants[id]
		if v == nil || v.ItemID != itemID {
			continue
		}
		out = append(out, MediaChoice{ID: "choice:video:" + v.ID, Kind: SelectionVideo, VariantID: v.ID, Width: v.Width, Height: v.Height, Bitrate: v.Bitrate, EstimatedSizeBytes: v.EstimatedSizeBytes, Codec: normalizeSelectorToken(v.Codec), Container: normalizeSelectorToken(v.Container)})
	}
	return out
}

func trackChoices(g *MediaGraph, itemID string, kind TrackKind) []MediaChoice {
	out := []MediaChoice{}
	choiceKind := SelectionAudio
	if kind == TrackSubtitle {
		choiceKind = SelectionSubtitle
	}
	for _, id := range keysOf(g.Tracks) {
		tr := g.Tracks[id]
		if tr == nil || tr.ItemID != itemID || tr.Kind != kind {
			continue
		}
		out = append(out, MediaChoice{ID: "choice:" + string(choiceKind) + ":" + tr.ID, Kind: choiceKind, TrackID: tr.ID, Codec: normalizeSelectorToken(tr.Codec), Language: normalizeLanguage(tr.Language)})
	}
	return out
}

func eligibleVideoChoices(videos []MediaChoice, c MediaSelectionConstraints) ([]MediaChoice, []SelectionDecision) {
	out := []MediaChoice{}
	decisions := []SelectionDecision{}
	for _, v := range videos {
		reason := ""
		if c.MaxWidth > 0 && v.Width > c.MaxWidth {
			reason = "rejected_width"
		} else if c.MaxHeight > 0 && v.Height > c.MaxHeight {
			reason = "rejected_height"
		} else if c.MaxBitrate > 0 && v.Bitrate > c.MaxBitrate {
			reason = "rejected_bitrate"
		} else if c.MaxEstimatedSizeBytes > 0 && v.EstimatedSizeBytes > c.MaxEstimatedSizeBytes {
			reason = "rejected_size"
		}
		if reason != "" {
			decisions = append(decisions, SelectionDecision{Code: reason, SubjectID: v.ID})
			continue
		}
		out = append(out, v)
	}
	return out, decisions
}

func defaultVideoLess(a, b MediaChoice) bool {
	if a.Width*a.Height != b.Width*b.Height {
		return a.Width*a.Height > b.Width*b.Height
	}
	if a.Bitrate != b.Bitrate {
		return a.Bitrate > b.Bitrate
	}
	if a.EstimatedSizeBytes != b.EstimatedSizeBytes {
		return a.EstimatedSizeBytes < b.EstimatedSizeBytes
	}
	return a.ID < b.ID
}

func selectedVideoLess(a, b MediaChoice, c MediaSelectionConstraints) bool {
	if preferredResolutionDistance(a, c) != preferredResolutionDistance(b, c) {
		return preferredResolutionDistance(a, c) < preferredResolutionDistance(b, c)
	}
	if codecRank(a.Codec, c.PreferredCodecs) != codecRank(b.Codec, c.PreferredCodecs) {
		return codecRank(a.Codec, c.PreferredCodecs) < codecRank(b.Codec, c.PreferredCodecs)
	}
	if codecRank(a.Container, c.PreferredContainers) != codecRank(b.Container, c.PreferredContainers) {
		return codecRank(a.Container, c.PreferredContainers) < codecRank(b.Container, c.PreferredContainers)
	}
	if a.Width*a.Height != b.Width*b.Height {
		return a.Width*a.Height > b.Width*b.Height
	}
	if a.Bitrate != b.Bitrate {
		return a.Bitrate > b.Bitrate
	}
	if a.EstimatedSizeBytes != b.EstimatedSizeBytes {
		return a.EstimatedSizeBytes < b.EstimatedSizeBytes
	}
	return a.ID < b.ID
}

func preferredResolutionDistance(choice MediaChoice, c MediaSelectionConstraints) int {
	if c.PreferredWidth <= 0 && c.PreferredHeight <= 0 {
		return 0
	}
	w := c.PreferredWidth
	if w <= 0 {
		w = choice.Width
	}
	h := c.PreferredHeight
	if h <= 0 {
		h = choice.Height
	}
	dw := choice.Width - w
	if dw < 0 {
		dw = -dw
	}
	dh := choice.Height - h
	if dh < 0 {
		dh = -dh
	}
	return dw*100000 + dh
}

func chooseLanguageTrack(tracks []MediaChoice, preferred []string, fallback bool) (*MediaChoice, SelectionDecision) {
	if len(tracks) == 0 {
		return nil, SelectionDecision{}
	}
	sorted := append([]MediaChoice(nil), tracks...)
	sort.SliceStable(sorted, func(i, j int) bool {
		ri := languageRank(sorted[i].Language, preferred)
		rj := languageRank(sorted[j].Language, preferred)
		if ri != rj {
			return ri < rj
		}
		if sorted[i].Codec != sorted[j].Codec {
			return sorted[i].Codec < sorted[j].Codec
		}
		return sorted[i].ID < sorted[j].ID
	})
	if len(preferred) > 0 && languageRank(sorted[0].Language, preferred) > len(normalizeLanguages(preferred)) {
		if !fallback {
			return nil, SelectionDecision{}
		}
		choice := sorted[0]
		return &choice, SelectionDecision{Code: "preferred_language_unavailable", SubjectID: choice.ID}
	}
	choice := sorted[0]
	return &choice, SelectionDecision{Code: "selected_" + string(choice.Kind), SubjectID: choice.ID}
}

func codecRank(value string, preferred []string) int {
	norm := normalizeSelectorToken(value)
	prefs := normalizeSelectorList(preferred)
	if len(prefs) == 0 {
		return 0
	}
	for i, p := range prefs {
		if p == norm {
			return i
		}
	}
	return len(prefs) + 1
}

func languageRank(value string, preferred []string) int {
	norm := normalizeLanguage(value)
	prefs := normalizeLanguages(preferred)
	if len(prefs) == 0 {
		return 0
	}
	for i, p := range prefs {
		if p == norm {
			return i
		}
	}
	return len(prefs) + 1
}

func sortChoices(choices []MediaChoice) {
	sort.SliceStable(choices, func(i, j int) bool {
		if choices[i].Kind != choices[j].Kind {
			return choices[i].Kind < choices[j].Kind
		}
		return choices[i].ID < choices[j].ID
	})
}

func choicePtr(c MediaChoice) *MediaChoice { return &c }

func normalizeSubtitlePolicy(policy SubtitlePolicy) SubtitlePolicy {
	switch policy {
	case SubtitleRequired, SubtitleDisabled:
		return policy
	default:
		return SubtitleOptional
	}
}

func normalizeSelectorList(values []string) []string {
	out := make([]string, 0, len(values))
	seen := map[string]bool{}
	for _, value := range values {
		n := normalizeSelectorToken(value)
		if n == "" || seen[n] {
			continue
		}
		seen[n] = true
		out = append(out, n)
	}
	return out
}

func normalizeLanguages(values []string) []string {
	out := make([]string, 0, len(values))
	seen := map[string]bool{}
	for _, value := range values {
		n := normalizeLanguage(value)
		if n == "" || seen[n] {
			continue
		}
		seen[n] = true
		out = append(out, n)
	}
	return out
}

func normalizeLanguage(value string) string {
	return strings.ToLower(strings.TrimSpace(value))
}

func normalizeSelectorToken(value string) string {
	return strings.ToLower(strings.TrimSpace(value))
}
