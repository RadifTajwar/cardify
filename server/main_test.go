package main

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
)

func TestCardLifecycle(t *testing.T) {
	dir := t.TempDir()
	s, err := openStore(dir)
	if err != nil {
		t.Fatal(err)
	}
	srv := httptest.NewServer(s.handler("secret"))
	defer srv.Close()

	call := func(method, path, token, body string, want int, out any) {
		t.Helper()
		req, _ := http.NewRequest(method, srv.URL+path, strings.NewReader(body))
		req.Header.Set("Authorization", "Bearer "+token)
		res, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer res.Body.Close()
		if res.StatusCode != want {
			b, _ := io.ReadAll(res.Body)
			t.Fatalf("%s %s: got %d, want %d: %s", method, path, res.StatusCode, want, b)
		}
		if out != nil {
			if err := json.NewDecoder(res.Body).Decode(out); err != nil {
				t.Fatal(err)
			}
		}
	}

	call("GET", "/api/cards", "wrong", "", 401, nil)
	call("POST", "/api/cards", "secret", `{"title":"CEO"}`, 400, nil)

	var c Card
	call("POST", "/api/cards", "secret", `{"id":"evil","hasImage":true,"name":"  Ada Lovelace ","company":"Analytical Engines"}`, 201, &c)
	if c.ID == "" || c.ID == "evil" || c.HasImage || c.Name != "Ada Lovelace" {
		t.Fatalf("create: %+v", c)
	}

	img := "/api/cards/" + c.ID + "/image"
	call("GET", img, "secret", "", 404, nil)
	call("PUT", img, "secret", "not a jpeg", 415, nil)
	var withImg Card
	call("PUT", img, "secret", "\xFF\xD8\xFF\xE0 pretend jpeg", 200, &withImg)
	if !withImg.HasImage {
		t.Fatal("image flag not set")
	}
	call("GET", img, "secret", "", 200, nil)

	var u Card
	call("PUT", "/api/cards/"+c.ID, "secret", `{"name":"Ada King","phone":"+44 20 7946 0000"}`, 200, &u)
	if u.Name != "Ada King" || u.Company != "" || !u.HasImage || !u.CreatedAt.Equal(c.CreatedAt) {
		t.Fatalf("update: %+v", u)
	}

	reopened, err := openStore(dir)
	if err != nil || reopened.cards[c.ID].Phone != "+44 20 7946 0000" {
		t.Fatalf("reload: %v %+v", err, reopened.cards)
	}

	call("DELETE", "/api/cards/"+c.ID, "secret", "", 204, nil)
	call("DELETE", "/api/cards/"+c.ID, "secret", "", 404, nil)
	if _, err := os.Stat(s.imagePath(c.ID)); !os.IsNotExist(err) {
		t.Fatalf("photo left behind: %v", err)
	}
	var list []Card
	call("GET", "/api/cards", "secret", "", 200, &list)
	if len(list) != 0 {
		t.Fatalf("list after delete: %+v", list)
	}
}
