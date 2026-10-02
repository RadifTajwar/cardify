package main

import (
	"context"
	"crypto/rand"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"strings"
	"testing"

	"github.com/jackc/pgx/v5"
)

func TestCardLifecycle(t *testing.T) {
	base := os.Getenv("CARDIFY_TEST_DATABASE_URL")
	if base == "" {
		t.Skip("set CARDIFY_TEST_DATABASE_URL to a direct (unpooled) Postgres URL to run this test")
	}
	ctx := context.Background()

	// A throwaway schema, so the test never touches real cards.
	schema := "test_" + strings.ToLower(rand.Text())
	admin, err := pgx.Connect(ctx, base)
	if err != nil {
		t.Fatal(err)
	}
	defer admin.Close(ctx)
	if _, err := admin.Exec(ctx, "CREATE SCHEMA "+schema); err != nil {
		t.Fatal(err)
	}
	defer admin.Exec(ctx, "DROP SCHEMA "+schema+" CASCADE")

	u, err := url.Parse(base)
	if err != nil {
		t.Fatal(err)
	}
	q := u.Query()
	q.Set("search_path", schema)
	u.RawQuery = q.Encode()
	s, err := openStore(ctx, u.String())
	if err != nil {
		t.Fatal(err)
	}
	defer s.db.Close()
	srv := httptest.NewServer(s.handler("secret"))
	defer srv.Close()

	call := func(method, path, token, body string, want int) []byte {
		t.Helper()
		req, _ := http.NewRequest(method, srv.URL+path, strings.NewReader(body))
		req.Header.Set("Authorization", "Bearer "+token)
		res, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer res.Body.Close()
		b, _ := io.ReadAll(res.Body)
		if res.StatusCode != want {
			t.Fatalf("%s %s: got %d, want %d: %s", method, path, res.StatusCode, want, b)
		}
		return b
	}
	card := func(b []byte) (c Card) {
		t.Helper()
		if err := json.Unmarshal(b, &c); err != nil {
			t.Fatal(err)
		}
		return c
	}

	call("GET", "/api/cards", "wrong", "", 401)
	call("POST", "/api/cards", "secret", `{"title":"CEO"}`, 400)
	// The app parses the list as a JSON array, so an empty list must be [] rather than null.
	if got := strings.TrimSpace(string(call("GET", "/api/cards", "secret", "", 200))); got != "[]" {
		t.Fatalf("empty list: %s", got)
	}

	c := card(call("POST", "/api/cards", "secret", `{"id":"evil","hasImage":true,"name":"  Ada Lovelace ","company":"Analytical Engines"}`, 201))
	if c.ID == "" || c.ID == "evil" || c.HasImage || c.Name != "Ada Lovelace" {
		t.Fatalf("create: %+v", c)
	}

	img := "/api/cards/" + c.ID + "/image"
	call("GET", img, "secret", "", 404)
	call("PUT", img, "secret", "not a jpeg", 415)
	const photo = "\xFF\xD8\xFF\xE0 pretend jpeg"
	if !card(call("PUT", img, "secret", photo, 200)).HasImage {
		t.Fatal("image flag not set")
	}
	if got := call("GET", img, "secret", "", 200); string(got) != photo {
		t.Fatalf("photo came back as %q", got)
	}

	u2 := card(call("PUT", "/api/cards/"+c.ID, "secret", `{"name":"Ada King","phone":"+44 20 7946 0000"}`, 200))
	if u2.Name != "Ada King" || u2.Company != "" || !u2.HasImage || !u2.CreatedAt.Equal(c.CreatedAt) {
		t.Fatalf("update: %+v", u2)
	}
	call("PUT", "/api/cards/nope", "secret", `{"name":"x"}`, 404)

	call("DELETE", "/api/cards/"+c.ID, "secret", "", 204)
	call("DELETE", "/api/cards/"+c.ID, "secret", "", 404)
	if got := strings.TrimSpace(string(call("GET", "/api/cards", "secret", "", 200))); got != "[]" {
		t.Fatalf("list after delete: %s", got)
	}
}
