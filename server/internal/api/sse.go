package api

import (
	"bytes"
	"encoding/json"
	"io"
	"log/slog"
	"sync"
)

// Hub fans events out to every open event stream. A client that can't keep up
// loses events rather than holding up the others; the next summary catches it up.
type Hub struct {
	mu   sync.Mutex
	subs map[chan Event]struct{}
	Log  *slog.Logger
}

type Event struct {
	Name string
	Data []byte
}

func NewHub() *Hub { return &Hub{subs: map[chan Event]struct{}{}} }

func (h *Hub) Subscribe() (<-chan Event, func()) {
	ch := make(chan Event, 16)
	h.mu.Lock()
	h.subs[ch] = struct{}{}
	h.mu.Unlock()
	return ch, func() {
		h.mu.Lock()
		delete(h.subs, ch)
		h.mu.Unlock()
	}
}

func (h *Hub) Publish(name string, v any) {
	b, err := json.Marshal(v)
	if err != nil {
		if h.Log != nil {
			h.Log.Error("marshal event", "event", name, "err", err)
		}
		return
	}
	ev := Event{name, b}
	h.mu.Lock()
	defer h.mu.Unlock()
	for ch := range h.subs {
		select {
		case ch <- ev:
		default:
		}
	}
}

func (h *Hub) Count() int {
	h.mu.Lock()
	defer h.mu.Unlock()
	return len(h.subs)
}

// WriteEvent writes one SSE event. Each line of data gets its own "data:" field,
// so a payload can never end the event early.
func WriteEvent(w io.Writer, name string, data []byte) error {
	var b bytes.Buffer
	if name != "" {
		b.WriteString("event: " + name + "\n")
	}
	for _, line := range bytes.Split(data, []byte("\n")) {
		b.WriteString("data: ")
		b.Write(bytes.TrimSuffix(line, []byte("\r")))
		b.WriteByte('\n')
	}
	b.WriteByte('\n')
	_, err := w.Write(b.Bytes())
	return err
}

// WriteComment writes an SSE comment, which clients ignore: a keep-alive.
func WriteComment(w io.Writer, text string) error {
	_, err := io.WriteString(w, ": "+text+"\n\n")
	return err
}
