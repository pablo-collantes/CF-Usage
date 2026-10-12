// Package store keeps the poller's history in SQLite: hourly usage per metric and
// resource, which days have been fetched, alerts, kill switches, settings and a
// poll log. Hours and days are stored as RFC 3339 / YYYY-MM-DD strings, which sort.
package store

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/pablo-collantes/cfusage/server/internal/model"
	"github.com/pablo-collantes/cfusage/server/internal/usage"

	_ "modernc.org/sqlite"
)

const schema = `
CREATE TABLE IF NOT EXISTS usage_hourly (
  metric TEXT NOT NULL, resource TEXT NOT NULL, hour TEXT NOT NULL, value REAL NOT NULL,
  PRIMARY KEY (metric, resource, hour));
CREATE INDEX IF NOT EXISTS usage_hourly_hour ON usage_hourly (hour);
CREATE TABLE IF NOT EXISTS fetched_days (day TEXT PRIMARY KEY, fetched_at TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS alerts (
  id TEXT PRIMARY KEY, kind TEXT NOT NULL, severity TEXT NOT NULL, metric TEXT,
  title TEXT NOT NULL, body TEXT NOT NULL, created_at TEXT NOT NULL, acked_at TEXT,
  suggested TEXT NOT NULL DEFAULT '[]');
CREATE INDEX IF NOT EXISTS alerts_created ON alerts (created_at);
CREATE TABLE IF NOT EXISTS kills (
  seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL, kind TEXT NOT NULL, target TEXT NOT NULL,
  label TEXT NOT NULL, description TEXT NOT NULL, state TEXT NOT NULL, applied_at TEXT NOT NULL,
  verified_at TEXT, restored_at TEXT, reason TEXT, error TEXT, prior TEXT NOT NULL DEFAULT '{}');
CREATE INDEX IF NOT EXISTS kills_id ON kills (id);
CREATE TABLE IF NOT EXISTS kv (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS polls (at TEXT NOT NULL, ok INTEGER NOT NULL, took_ms INTEGER NOT NULL, calls INTEGER NOT NULL, error TEXT);
`

type Store struct{ db *sql.DB }

func Open(path string) (*Store, error) {
	dsn := "file:" + path + "?_pragma=busy_timeout(5000)&_pragma=journal_mode(WAL)&_pragma=synchronous(NORMAL)"
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(1) // one writer; SQLite serialises anyway and this avoids SQLITE_BUSY
	if _, err := db.Exec(schema); err != nil {
		db.Close()
		return nil, fmt.Errorf("schema: %w", err)
	}
	return &Store{db: db}, nil
}

func (s *Store) Close() error { return s.db.Close() }

const hourFmt = time.RFC3339

// ReplaceHourly swaps every hourly row in [from, to) for points (which must all
// fall in that window), in one transaction.
func (s *Store) ReplaceHourly(ctx context.Context, from, to time.Time, points []usage.Point) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if _, err := tx.ExecContext(ctx, `DELETE FROM usage_hourly WHERE hour >= ? AND hour < ?`,
		from.UTC().Format(hourFmt), to.UTC().Format(hourFmt)); err != nil {
		return err
	}
	st, err := tx.PrepareContext(ctx, `INSERT INTO usage_hourly (metric, resource, hour, value) VALUES (?, ?, ?, ?)
		ON CONFLICT (metric, resource, hour) DO UPDATE SET value = value + excluded.value`)
	if err != nil {
		return err
	}
	defer st.Close()
	for _, p := range points {
		if _, err := st.ExecContext(ctx, p.Metric, p.Resource, p.Bucket, p.Value); err != nil {
			return err
		}
	}
	return tx.Commit()
}

// HourRow is one stored hourly amount.
type HourRow struct {
	Metric, Resource string
	Hour             time.Time
	Value            float64
}

// Hourly returns rows of the given metrics (all when none given) in [from, to).
func (s *Store) Hourly(ctx context.Context, from, to time.Time, metrics ...string) ([]HourRow, error) {
	q := `SELECT metric, resource, hour, value FROM usage_hourly WHERE hour >= ? AND hour < ?`
	args := []any{from.UTC().Format(hourFmt), to.UTC().Format(hourFmt)}
	if len(metrics) > 0 {
		q += ` AND metric IN (` + strings.TrimSuffix(strings.Repeat("?,", len(metrics)), ",") + `)`
		for _, m := range metrics {
			args = append(args, m)
		}
	}
	rows, err := s.db.QueryContext(ctx, q+` ORDER BY hour`, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []HourRow
	for rows.Next() {
		var r HourRow
		var h string
		if err := rows.Scan(&r.Metric, &r.Resource, &h, &r.Value); err != nil {
			return nil, err
		}
		if r.Hour, err = time.Parse(hourFmt, h); err != nil {
			return nil, err
		}
		out = append(out, r)
	}
	return out, rows.Err()
}

// PruneHourly drops hourly rows older than before.
func (s *Store) PruneHourly(ctx context.Context, before time.Time) error {
	_, err := s.db.ExecContext(ctx, `DELETE FROM usage_hourly WHERE hour < ?`, before.UTC().Format(hourFmt))
	return err
}

func (s *Store) MarkFetched(ctx context.Context, at time.Time, days ...string) error {
	for _, d := range days {
		if _, err := s.db.ExecContext(ctx, `INSERT INTO fetched_days (day, fetched_at) VALUES (?, ?)
			ON CONFLICT (day) DO UPDATE SET fetched_at = excluded.fetched_at`, d, at.UTC().Format(hourFmt)); err != nil {
			return err
		}
	}
	return nil
}

// ForgetFetched clears the fetch log, so every day is read again.
func (s *Store) ForgetFetched(ctx context.Context) error {
	_, err := s.db.ExecContext(ctx, `DELETE FROM fetched_days`)
	return err
}

// FetchedDays maps a day to the last time all of it was fetched.
func (s *Store) FetchedDays(ctx context.Context) (map[string]time.Time, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT day, fetched_at FROM fetched_days`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[string]time.Time{}
	for rows.Next() {
		var d, at string
		if err := rows.Scan(&d, &at); err != nil {
			return nil, err
		}
		t, _ := time.Parse(hourFmt, at)
		out[d] = t
	}
	return out, rows.Err()
}

// --- key/value: settings and the last summary ---

func (s *Store) GetJSON(ctx context.Context, key string, out any) (bool, error) {
	var v string
	err := s.db.QueryRowContext(ctx, `SELECT value FROM kv WHERE key = ?`, key).Scan(&v)
	if errors.Is(err, sql.ErrNoRows) {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	return true, json.Unmarshal([]byte(v), out)
}

func (s *Store) PutJSON(ctx context.Context, key string, v any) error {
	b, err := json.Marshal(v)
	if err != nil {
		return err
	}
	_, err = s.db.ExecContext(ctx, `INSERT INTO kv (key, value) VALUES (?, ?)
		ON CONFLICT (key) DO UPDATE SET value = excluded.value`, key, string(b))
	return err
}

// --- poll log ---

func (s *Store) LogPoll(ctx context.Context, at time.Time, took time.Duration, calls int64, perr error) error {
	var e *string
	if perr != nil {
		m := perr.Error()
		e = &m
	}
	ok := 0
	if perr == nil {
		ok = 1
	}
	_, err := s.db.ExecContext(ctx, `INSERT INTO polls (at, ok, took_ms, calls, error) VALUES (?, ?, ?, ?, ?)`,
		at.UTC().Format(hourFmt), ok, took.Milliseconds(), calls, e)
	if err == nil {
		_, err = s.db.ExecContext(ctx, `DELETE FROM polls WHERE at < ?`, at.AddDate(0, 0, -7).UTC().Format(hourFmt))
	}
	return err
}

// --- alerts ---

// InsertAlert stores a new alert. It reports false when an alert with that id
// already exists, which is how each alert fires only once.
func (s *Store) InsertAlert(ctx context.Context, a model.Alert) (bool, error) {
	sug, _ := json.Marshal(a.Suggested)
	res, err := s.db.ExecContext(ctx, `INSERT OR IGNORE INTO alerts (id, kind, severity, metric, title, body, created_at, acked_at, suggested)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`, a.ID, a.Kind, a.Severity, a.Metric, a.Title, a.Body, a.CreatedAt, a.AckedAt, string(sug))
	if err != nil {
		return false, err
	}
	n, _ := res.RowsAffected()
	return n == 1, nil
}

func (s *Store) AlertExists(ctx context.Context, id string) (bool, error) {
	var n int
	err := s.db.QueryRowContext(ctx, `SELECT COUNT(*) FROM alerts WHERE id = ?`, id).Scan(&n)
	return n > 0, err
}

// Alerts returns alerts newest first; unacked limits it to unacknowledged ones.
func (s *Store) Alerts(ctx context.Context, limit int, unacked bool) ([]model.Alert, error) {
	q := `SELECT id, kind, severity, metric, title, body, created_at, acked_at, suggested FROM alerts`
	if unacked {
		q += ` WHERE acked_at IS NULL`
	}
	rows, err := s.db.QueryContext(ctx, q+` ORDER BY created_at DESC, id LIMIT ?`, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []model.Alert{}
	for rows.Next() {
		var a model.Alert
		var sug string
		if err := rows.Scan(&a.ID, &a.Kind, &a.Severity, &a.Metric, &a.Title, &a.Body, &a.CreatedAt, &a.AckedAt, &sug); err != nil {
			return nil, err
		}
		a.Suggested = []model.KillTarget{}
		_ = json.Unmarshal([]byte(sug), &a.Suggested)
		out = append(out, a)
	}
	return out, rows.Err()
}

var ErrNotFound = errors.New("not found")

func (s *Store) AckAlert(ctx context.Context, id string, at time.Time) (model.Alert, error) {
	res, err := s.db.ExecContext(ctx, `UPDATE alerts SET acked_at = COALESCE(acked_at, ?) WHERE id = ?`, model.TS(at), id)
	if err != nil {
		return model.Alert{}, err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return model.Alert{}, ErrNotFound
	}
	all, err := s.Alerts(ctx, 1000, false)
	if err != nil {
		return model.Alert{}, err
	}
	for _, a := range all {
		if a.ID == id {
			return a, nil
		}
	}
	return model.Alert{}, ErrNotFound
}

// AckAlertsOfKind acknowledges every open alert of a kind (used when the
// monitor recovers).
func (s *Store) AckAlertsOfKind(ctx context.Context, kind string, at time.Time) error {
	_, err := s.db.ExecContext(ctx, `UPDATE alerts SET acked_at = ? WHERE kind = ? AND acked_at IS NULL`, model.TS(at), kind)
	return err
}

// --- kills ---

// KillRecord is a kill plus what it needs to be undone.
type KillRecord struct {
	Seq   int64
	Kill  model.Kill
	Prior json.RawMessage
}

func (s *Store) InsertKill(ctx context.Context, k model.Kill, prior any) (int64, error) {
	p, _ := json.Marshal(prior)
	res, err := s.db.ExecContext(ctx, `INSERT INTO kills (id, kind, target, label, description, state, applied_at, verified_at, restored_at, reason, error, prior)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`, k.ID, k.Kind, k.Target, k.Label, k.Description, k.State,
		k.AppliedAt, k.VerifiedAt, k.RestoredAt, k.Reason, k.Error, string(p))
	if err != nil {
		return 0, err
	}
	return res.LastInsertId()
}

// UpdateKill saves a kill's state fields, and prior when it is not nil.
func (s *Store) UpdateKill(ctx context.Context, seq int64, k model.Kill, prior any) error {
	if prior != nil {
		p, _ := json.Marshal(prior)
		_, err := s.db.ExecContext(ctx, `UPDATE kills SET state = ?, verified_at = ?, restored_at = ?, error = ?, prior = ? WHERE seq = ?`,
			k.State, k.VerifiedAt, k.RestoredAt, k.Error, string(p), seq)
		return err
	}
	_, err := s.db.ExecContext(ctx, `UPDATE kills SET state = ?, verified_at = ?, restored_at = ?, error = ? WHERE seq = ?`,
		k.State, k.VerifiedAt, k.RestoredAt, k.Error, seq)
	return err
}

// MarkKillVerified sets verified_at if the kill is still active, and returns it.
func (s *Store) MarkKillVerified(ctx context.Context, seq int64, at time.Time) (KillRecord, bool, error) {
	res, err := s.db.ExecContext(ctx, `UPDATE kills SET verified_at = ? WHERE seq = ? AND state = 'active'`, model.TS(at), seq)
	if err != nil {
		return KillRecord{}, false, err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return KillRecord{}, false, nil
	}
	r, err := scanKill(s.db.QueryRowContext(ctx, `SELECT `+killCols+` FROM kills WHERE seq = ?`, seq))
	return r, err == nil, err
}

func scanKill(sc interface{ Scan(...any) error }) (KillRecord, error) {
	var r KillRecord
	var p string
	k := &r.Kill
	err := sc.Scan(&r.Seq, &k.ID, &k.Kind, &k.Target, &k.Label, &k.Description, &k.State, &k.AppliedAt,
		&k.VerifiedAt, &k.RestoredAt, &k.Reason, &k.Error, &p)
	r.Prior = json.RawMessage(p)
	return r, err
}

const killCols = `seq, id, kind, target, label, description, state, applied_at, verified_at, restored_at, reason, error, prior`

// Kills returns kill history newest first.
func (s *Store) Kills(ctx context.Context, limit int) ([]KillRecord, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT `+killCols+` FROM kills ORDER BY seq DESC LIMIT ?`, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []KillRecord
	for rows.Next() {
		r, err := scanKill(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, r)
	}
	return out, rows.Err()
}

// CurrentKill is the newest record with that id that hasn't been restored or
// failed, i.e. the one a restore acts on.
func (s *Store) CurrentKill(ctx context.Context, id string) (KillRecord, error) {
	r, err := scanKill(s.db.QueryRowContext(ctx, `SELECT `+killCols+` FROM kills
		WHERE id = ? AND state NOT IN ('restored', 'failed') ORDER BY seq DESC LIMIT 1`, id))
	if errors.Is(err, sql.ErrNoRows) {
		return r, ErrNotFound
	}
	return r, err
}

// OpenKills are the kills not restored, including recent failures.
func (s *Store) OpenKills(ctx context.Context, failedSince time.Time) ([]model.Kill, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT `+killCols+` FROM kills
		WHERE state IN ('applying', 'active', 'restoring') OR (state = 'failed' AND applied_at >= ?)
		ORDER BY seq DESC`, model.TS(failedSince))
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []model.Kill{}
	for rows.Next() {
		r, err := scanKill(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, r.Kill)
	}
	return out, rows.Err()
}
