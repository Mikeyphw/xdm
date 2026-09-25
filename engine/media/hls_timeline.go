package media

import (
	"fmt"
	"strings"
)

type HLSStreamKind string

const (
	HLSVOD   HLSStreamKind = "vod"
	HLSEvent HLSStreamKind = "event"
	HLSLive  HLSStreamKind = "live"
)

type HLSTimelineEntry struct {
	Identity   FragmentIdentity `json:"identity"`
	Segment    HLSSegment       `json:"segment"`
	State      FragmentState    `json:"state"`
	Historical bool             `json:"historical"`
	Redownload bool             `json:"redownload"`
}

type HLSTimelineReconcileResult struct {
	Kind       HLSStreamKind      `json:"kind"`
	Ended      bool               `json:"ended"`
	Entries    []HLSTimelineEntry `json:"entries"`
	Retained   int                `json:"retained"`
	Appended   int                `json:"appended"`
	Historical int                `json:"historical"`
}

func HLSTimelineIdentity(seg HLSSegment) (FragmentIdentity, error) {
	res, err := NewMediaResource(MediaResourceInput{TransportURL: seg.ResolvedURI})
	if err != nil {
		return FragmentIdentity{}, err
	}
	enc := ""
	if seg.Key != nil {
		enc = strings.Join([]string{string(seg.Key.Method), seg.Key.ResolvedURI, seg.Key.IV}, "|")
	}
	timeline := fmt.Sprintf("hls:%d:%d", seg.DiscontinuitySequence, seg.MediaSequence)
	return FragmentIdentity{Protocol: FragmentProtocolHLS, TimelineKey: timeline, ResourceID: res.ResourceID, Range: cloneByteRange(seg.Range), EncryptionID: enc}, nil
}

func ReconcileHLSTimeline(previous []FragmentRecord, playlist HLSPlaylist) (HLSTimelineReconcileResult, error) {
	prev := map[string]FragmentRecord{}
	for _, r := range previous {
		if id, err := FragmentID(r.Identity); err == nil {
			prev[id] = r
		}
	}
	seen := map[string]bool{}
	result := HLSTimelineReconcileResult{Kind: classifyHLSStream(playlist), Ended: playlist.EndList}
	for _, seg := range playlist.Segments {
		identity, err := HLSTimelineIdentity(seg)
		if err != nil {
			return result, err
		}
		id, _ := FragmentID(identity)
		if seen[id] {
			continue
		}
		seen[id] = true
		state := FragmentPending
		redownload := true
		if rec, ok := prev[id]; ok {
			state = rec.State
			if rec.State == FragmentCommitted || rec.State == FragmentHistorical {
				redownload = false
				result.Retained++
			}
		} else {
			result.Appended++
		}
		result.Entries = append(result.Entries, HLSTimelineEntry{Identity: identity, Segment: seg, State: state, Redownload: redownload})
	}
	for id, rec := range prev {
		if seen[id] || rec.State != FragmentCommitted {
			continue
		}
		result.Entries = append(result.Entries, HLSTimelineEntry{Identity: rec.Identity, State: FragmentHistorical, Historical: true, Redownload: false})
		result.Historical++
	}
	return result, nil
}

func classifyHLSStream(p HLSPlaylist) HLSStreamKind {
	if p.EndList || p.PlaylistType == "vod" {
		return HLSVOD
	}
	if p.PlaylistType == "event" {
		return HLSEvent
	}
	return HLSLive
}
