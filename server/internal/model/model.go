// Package model holds the JSON types of the v1 API (docs/API.md). Optional fields are
// pointers so they marshal as null, and slices are kept non-nil so they marshal as [].
package model

import "time"

// TS formats a time the way the API does: RFC 3339, UTC, whole seconds.
func TS(t time.Time) string { return t.UTC().Truncate(time.Second).Format(time.RFC3339) }

// TSP is TS for an optional time; the zero time becomes null.
func TSP(t time.Time) *string {
	if t.IsZero() {
		return nil
	}
	s := TS(t)
	return &s
}

func Str(s string) *string {
	if s == "" {
		return nil
	}
	return &s
}

type NameValue struct {
	Name  string  `json:"name"`
	Value float64 `json:"value"`
}

type DateValue struct {
	Date  string  `json:"date"`
	Value float64 `json:"value"`
}

type TimeValue struct {
	T     string  `json:"t"`
	Value float64 `json:"value"`
}

type Overage struct {
	Units float64 `json:"units"`
	USD   float64 `json:"usd"`
}

type Metric struct {
	Key          string      `json:"key"`
	Label        string      `json:"label"`
	Group        string      `json:"group"`
	Unit         string      `json:"unit"`
	Kind         string      `json:"kind"`
	Used         float64     `json:"used"`
	Included     float64     `json:"included"`
	Pct          float64     `json:"pct"`
	Projected    float64     `json:"projected"`
	ProjectedPct float64     `json:"projectedPct"`
	Rate24h      float64     `json:"rate24h"`
	Status       string      `json:"status"`
	Overage      Overage     `json:"overage"`
	Price        string      `json:"price"`
	Sampled      bool        `json:"sampled"`
	Top          []NameValue `json:"top"`
	Daily        []DateValue `json:"daily"`
}

type Cycle struct {
	Start    string  `json:"start"`
	End      string  `json:"end"`
	CycleDay int     `json:"cycleDay"`
	Elapsed  float64 `json:"elapsed"`
	DaysLeft float64 `json:"daysLeft"`
}

type Summary struct {
	GeneratedAt      string   `json:"generatedAt"`
	LastPollAt       *string  `json:"lastPollAt"`
	PollError        *string  `json:"pollError"`
	DataAsOf         *string  `json:"dataAsOf"`
	Cycle            Cycle    `json:"cycle"`
	Status           string   `json:"status"`
	EstimatedBillUSD float64  `json:"estimatedBillUsd"`
	Metrics          []Metric `json:"metrics"`
	Alerts           []Alert  `json:"alerts"`
	Kills            []Kill   `json:"kills"`
}

type KillTarget struct {
	Kind        string `json:"kind"`
	Target      string `json:"target"`
	Label       string `json:"label"`
	Description string `json:"description"`
}

type Kill struct {
	KillTarget
	ID         string  `json:"id"`
	State      string  `json:"state"`
	AppliedAt  string  `json:"appliedAt"`
	VerifiedAt *string `json:"verifiedAt"`
	RestoredAt *string `json:"restoredAt"`
	Reason     *string `json:"reason"`
	Error      *string `json:"error"`
}

// Kill states.
const (
	KillApplying  = "applying"
	KillActive    = "active"
	KillRestoring = "restoring"
	KillRestored  = "restored"
	KillFailed    = "failed"
)

// Kill kinds.
const (
	KindWAFBlock      = "waf_block"
	KindWorkersDevOff = "workers_dev_off"
	KindCronOff       = "cron_off"
	KindQueuePause    = "queue_pause"
)

type Alert struct {
	ID        string       `json:"id"`
	Kind      string       `json:"kind"`
	Severity  string       `json:"severity"`
	Metric    *string      `json:"metric"`
	Title     string       `json:"title"`
	Body      string       `json:"body"`
	CreatedAt string       `json:"createdAt"`
	AckedAt   *string      `json:"ackedAt"`
	Suggested []KillTarget `json:"suggested"`
}

type ResourceShare struct {
	Name  string  `json:"name"`
	Value float64 `json:"value"`
	Share float64 `json:"share"`
}

type MetricDetail struct {
	Metric     Metric          `json:"metric"`
	Range      string          `json:"range"`
	Bucket     string          `json:"bucket"`
	Series     []TimeValue     `json:"series"`
	Cumulative []TimeValue     `json:"cumulative"`
	ByResource []ResourceShare `json:"byResource"`
}

type WorkerRow struct {
	Name            string       `json:"name"`
	Kind            string       `json:"kind"`
	Requests        float64      `json:"requests"`
	Errors          float64      `json:"errors"`
	ErrorRate       float64      `json:"errorRate"`
	CPUMs           float64      `json:"cpuMs"`
	CPUMsPerRequest float64      `json:"cpuMsPerRequest"`
	Subrequests     float64      `json:"subrequests"`
	ShareOfRequests float64      `json:"shareOfRequests"`
	Hosts           []string     `json:"hosts"`
	Spark           []float64    `json:"spark"`
	Killable        []KillTarget `json:"killable"`
}

type WorkerPoint struct {
	T        string  `json:"t"`
	Requests float64 `json:"requests"`
	Errors   float64 `json:"errors"`
	CPUMs    float64 `json:"cpuMs"`
}

type ColoCount struct {
	Colo     string  `json:"colo"`
	Requests float64 `json:"requests"`
}

type WorkerDetail struct {
	Row    WorkerRow     `json:"row"`
	Bucket string        `json:"bucket"`
	Series []WorkerPoint `json:"series"`
	Colos  []ColoCount   `json:"colos"`
	Site   *SiteDetail   `json:"site"`
}

type SiteRow struct {
	Host        string       `json:"host"`
	Zone        string       `json:"zone"`
	Requests    float64      `json:"requests"`
	Bytes       float64      `json:"bytes"`
	Blocked     float64      `json:"blocked"`
	CachedShare float64      `json:"cachedShare"`
	Status4xx   float64      `json:"status4xx"`
	Status5xx   float64      `json:"status5xx"`
	Service     *string      `json:"service"`
	Spark       []float64    `json:"spark"`
	Killable    []KillTarget `json:"killable"`
}

type SitePoint struct {
	T        string  `json:"t"`
	Requests float64 `json:"requests"`
	Blocked  float64 `json:"blocked"`
}

type PathCount struct {
	Path     string  `json:"path"`
	Requests float64 `json:"requests"`
}

type CountryCount struct {
	Country  string  `json:"country"`
	Requests float64 `json:"requests"`
}

type StatusCount struct {
	Status   int     `json:"status"`
	Requests float64 `json:"requests"`
}

type ActionCount struct {
	Action   string  `json:"action"`
	Requests float64 `json:"requests"`
}

type AgentCount struct {
	Agent    string  `json:"agent"`
	Requests float64 `json:"requests"`
}

type CacheCount struct {
	Status   string  `json:"status"`
	Requests float64 `json:"requests"`
}

type SiteDetail struct {
	Row             SiteRow        `json:"row"`
	Bucket          string         `json:"bucket"`
	Series          []SitePoint    `json:"series"`
	Paths           []PathCount    `json:"paths"`
	Countries       []CountryCount `json:"countries"`
	Statuses        []StatusCount  `json:"statuses"`
	SecurityActions []ActionCount  `json:"securityActions"`
	UserAgents      []AgentCount   `json:"userAgents"`
	Cache           []CacheCount   `json:"cache"`
}

type Thresholds struct {
	Info     float64 `json:"info"`
	Warn     float64 `json:"warn"`
	Critical float64 `json:"critical"`
}

type Settings struct {
	CycleDay        int        `json:"cycleDay"`
	Thresholds      Thresholds `json:"thresholds"`
	ProjectionAlert bool       `json:"projectionAlert"`
	SpikeFactor     float64    `json:"spikeFactor"`
	SpikeMinShare   float64    `json:"spikeMinShare"`
	Muted           []string   `json:"muted"`
}

func DefaultSettings(cycleDay int) Settings {
	return Settings{
		CycleDay:        cycleDay,
		Thresholds:      Thresholds{Info: 0.5, Warn: 0.75, Critical: 0.9},
		ProjectionAlert: true,
		SpikeFactor:     5,
		SpikeMinShare:   0.01,
		Muted:           []string{},
	}
}

type KillList struct {
	Available []KillTarget `json:"available"`
	Kills     []Kill       `json:"kills"`
}
