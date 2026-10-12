// Package cf is a small Cloudflare API client: GraphQL Analytics and REST v4.
//
// The token is read from its file on every call, so rotating it needs no restart,
// and it is only ever placed in the Authorization header.
package cf

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"strings"
	"sync/atomic"
	"time"
)

const DefaultBase = "https://api.cloudflare.com/client/v4"

type Client struct {
	Base      string
	TokenFile string
	HTTP      *http.Client
	calls     atomic.Int64
}

func New(tokenFile string) *Client {
	return &Client{Base: DefaultBase, TokenFile: tokenFile, HTTP: &http.Client{Timeout: 60 * time.Second}}
}

// Calls is the number of API requests made so far.
func (c *Client) Calls() int64 { return c.calls.Load() }

func (c *Client) token() (string, error) {
	b, err := os.ReadFile(c.TokenFile)
	if err != nil {
		return "", fmt.Errorf("token: %w", err)
	}
	t := strings.TrimSpace(string(b))
	if t == "" {
		return "", errors.New("token file is empty")
	}
	return t, nil
}

func (c *Client) do(ctx context.Context, method, url string, body any) ([]byte, int, error) {
	tok, err := c.token()
	if err != nil {
		return nil, 0, err
	}
	var rd io.Reader
	if body != nil {
		b, err := json.Marshal(body)
		if err != nil {
			return nil, 0, err
		}
		rd = bytes.NewReader(b)
	}
	req, err := http.NewRequestWithContext(ctx, method, url, rd)
	if err != nil {
		return nil, 0, err
	}
	req.Header.Set("Authorization", "Bearer "+tok)
	req.Header.Set("Content-Type", "application/json")
	c.calls.Add(1)
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return nil, 0, err
	}
	defer resp.Body.Close()
	b, err := io.ReadAll(io.LimitReader(resp.Body, 64<<20))
	return b, resp.StatusCode, err
}

// GraphQLError carries the messages of a response's errors array. GraphQL answers
// HTTP 200 even when a query fails, so that array is the only signal.
type GraphQLError struct{ Messages []string }

func (e *GraphQLError) Error() string { return "graphql: " + strings.Join(e.Messages, "; ") }

// GraphQL runs a query and decodes its data into out.
func (c *Client) GraphQL(ctx context.Context, query string, vars map[string]any, out any) error {
	b, status, err := c.do(ctx, http.MethodPost, c.Base+"/graphql", map[string]any{"query": query, "variables": vars})
	if err != nil {
		return err
	}
	var r struct {
		Data   json.RawMessage `json:"data"`
		Errors []struct {
			Message string `json:"message"`
			Path    []any  `json:"path"`
		} `json:"errors"`
	}
	if err := json.Unmarshal(b, &r); err != nil {
		return fmt.Errorf("graphql: HTTP %d: %s", status, snippet(b))
	}
	if len(r.Errors) > 0 {
		e := &GraphQLError{}
		for _, x := range r.Errors {
			m := x.Message
			if len(x.Path) > 0 {
				m += fmt.Sprintf(" %v", x.Path)
			}
			e.Messages = append(e.Messages, m)
		}
		return e
	}
	if status != http.StatusOK {
		return fmt.Errorf("graphql: HTTP %d", status)
	}
	if out == nil || len(r.Data) == 0 || string(r.Data) == "null" {
		return nil
	}
	return json.Unmarshal(r.Data, out)
}

// APIError is a REST v4 failure: success false or a non-2xx status.
type APIError struct {
	Status   int
	Messages []string
}

func (e *APIError) Error() string {
	if len(e.Messages) == 0 {
		return fmt.Sprintf("cloudflare: HTTP %d", e.Status)
	}
	return fmt.Sprintf("cloudflare: HTTP %d: %s", e.Status, strings.Join(e.Messages, "; "))
}

// REST calls a v4 endpoint (path without the base, e.g. "zones") and decodes
// its result into out.
func (c *Client) REST(ctx context.Context, method, path string, body, out any) error {
	b, status, err := c.do(ctx, method, c.Base+"/"+strings.TrimPrefix(path, "/"), body)
	if err != nil {
		return err
	}
	var r struct {
		Success bool            `json:"success"`
		Result  json.RawMessage `json:"result"`
		Errors  []struct {
			Code    int    `json:"code"`
			Message string `json:"message"`
		} `json:"errors"`
	}
	if err := json.Unmarshal(b, &r); err != nil {
		return &APIError{Status: status, Messages: []string{snippet(b)}}
	}
	if !r.Success || status/100 != 2 {
		e := &APIError{Status: status}
		for _, x := range r.Errors {
			e.Messages = append(e.Messages, fmt.Sprintf("%d %s", x.Code, x.Message))
		}
		return e
	}
	if out == nil || len(r.Result) == 0 || string(r.Result) == "null" {
		return nil
	}
	return json.Unmarshal(r.Result, out)
}

func snippet(b []byte) string {
	s := strings.TrimSpace(string(b))
	if len(s) > 200 {
		s = s[:200] + "…"
	}
	return s
}

// IsNotFound reports a REST 404.
func IsNotFound(err error) bool {
	var e *APIError
	return errors.As(err, &e) && e.Status == http.StatusNotFound
}
