package tracedoctor

import "strings"

type timeline struct {
	list []timelineEntry
}

type timelineEntry struct {
	when float64
	what string
}

func (t *timeline) durationOf(name string) float64 {
	for i, entry := range t.list {
		if entry.what == name && i < len(t.list)-1 {
			return t.list[i+1].when - entry.when
		}
	}
	return 0 // Return 0 if the name is not found or no next entry exists
}

func (t *timeline) impact() float64 {
	if len(t.list) < 2 {
		return 0
	}
	return t.list[len(t.list)-1].when - t.list[0].when
}

func (t *timeline) add(when float64, what string) {
	t.list = append(t.list, timelineEntry{when, what})
}

func (t *timeline) addComment(what string) {
	t.add(-1.0, what)
}

func (t *timeline) render(out *output) {
	for _, entry := range t.list {
		if entry.when < 0.0 {
			out.fmt("%s%s\n", strings.Repeat(" ", 15), entry.what)
		} else {
			out.fmt("%10.3f ms: %s\n", entry.when, entry.what)
		}
	}
}
