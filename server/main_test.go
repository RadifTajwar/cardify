package main

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/sha1"
	"encoding/hex"
	"encoding/json"
	"image"
	"image/jpeg"
	"io"
	"maps"
	"net/http"
	"net/http/httptest"
	"os"
	"regexp"
	"slices"
	"strings"
	"sync"
	"testing"
)

const photo = "\xFF\xD8\xFF\xE0 pretend jpeg"

func TestAPI(t *testing.T) {
	uri := os.Getenv("CARDIFY_TEST_MONGODB_URI")
	if uri == "" {
		t.Skip("set CARDIFY_TEST_MONGODB_URI (e.g. mongodb://localhost:27017) to run this test")
	}
	ctx := context.Background()
	s, err := openStore(ctx, uri, "test_"+strings.ToLower(rand.Text())) // a throwaway database, so tests never touch real cards
	if err != nil {
		t.Fatal(err)
	}
	defer s.db.Client().Disconnect(ctx)
	defer s.db.Drop(ctx)
	fake := newFakeCloudinary(t)
	s.photos = &cloudinary{fake.URL, "demo", "key", "secret"}
	srv := httptest.NewServer(s.handler())
	defer srv.Close()

	call := func(method, path, token, body string, want int) []byte {
		t.Helper()
		req, _ := http.NewRequest(method, srv.URL+path, strings.NewReader(body))
		if token != "" {
			req.Header.Set("Authorization", "Bearer "+token)
		}
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
	decode := func(b []byte, v any) {
		t.Helper()
		if err := json.Unmarshal(b, v); err != nil {
			t.Fatalf("%v: %s", err, b)
		}
	}
	token := func(b []byte) string {
		var res struct{ Token string }
		decode(b, &res)
		return res.Token
	}
	card := func(b []byte) (c Card) {
		decode(b, &c)
		return c
	}
	cards := func(b []byte) (list []Card) {
		decode(b, &list)
		return list
	}

	// Accounts.
	call("POST", "/api/auth/signup", "", `{"name":"Ada","email":"not an email","password":"longenough"}`, 400)
	call("POST", "/api/auth/signup", "", `{"name":"Ada","email":"ada@example.com","password":"short"}`, 400)
	call("POST", "/api/auth/signup", "", `{"name":" ","email":"ada@example.com","password":"longenough"}`, 400)
	ada := token(call("POST", "/api/auth/signup", "", `{"name":"Ada","email":" Ada@Example.com ","password":"longenough"}`, 201))
	call("POST", "/api/auth/signup", "", `{"name":"Ada again","email":"ada@example.com","password":"longenough"}`, 409)
	call("POST", "/api/auth/login", "", `{"email":"ada@example.com","password":"wrong password"}`, 401)
	call("POST", "/api/auth/login", "", `{"email":"nobody@example.com","password":"longenough"}`, 401)
	ada2 := token(call("POST", "/api/auth/login", "", `{"email":"ADA@example.com","password":"longenough"}`, 200))
	call("GET", "/api/cards", ada2, "", 200)
	call("POST", "/api/auth/logout", ada2, "", 204)
	call("GET", "/api/cards", ada2, "", 401)
	call("GET", "/api/cards", "", "", 401)
	call("GET", "/api/cards", "made-up", "", 401)
	bob := token(call("POST", "/api/auth/signup", "", `{"name":"Bob","email":"bob@example.com","password":"longenough"}`, 201))

	// Cards start private, and the server owns id, owner and photo.
	call("POST", "/api/cards", ada, `{"title":"CEO"}`, 400)
	if got := strings.TrimSpace(string(call("GET", "/api/cards", ada, "", 200))); got != "[]" {
		t.Fatalf("empty list: %s", got) // the app parses a JSON array, so not null
	}
	priv := card(call("POST", "/api/cards", ada, `{"id":"evil","ownerId":"x","photoUrl":"x","name":"  Grace Hopper ","company":"Navy"}`, 201))
	if priv.ID == "evil" || priv.Name != "Grace Hopper" || priv.Public || !priv.Mine || priv.PhotoURL != "" || priv.OwnerName != "Ada" {
		t.Fatalf("create: %+v", priv)
	}
	pub := card(call("POST", "/api/cards", ada, `{"name":"Alan Turing","company":"Bletchley Park","public":true}`, 201))
	if got := cards(call("GET", "/api/cards", ada, "", 200)); len(got) != 2 {
		t.Fatalf("ada's cards: %+v", got)
	}

	// Bob sees only Ada's public card, and can't change either of hers.
	if got := cards(call("GET", "/api/cards", bob, "", 200)); len(got) != 0 {
		t.Fatalf("bob's cards: %+v", got)
	}
	if got := cards(call("GET", "/api/cards/public", bob, "", 200)); len(got) != 1 || got[0].ID != pub.ID || got[0].Mine || got[0].OwnerName != "Ada" {
		t.Fatalf("public cards for bob: %+v", got)
	}
	if got := cards(call("GET", "/api/cards/public?q=bletchley+P", bob, "", 200)); len(got) != 1 {
		t.Fatalf("search by company: %+v", got)
	}
	if got := cards(call("GET", "/api/cards/public?q=hopper", bob, "", 200)); len(got) != 0 {
		t.Fatalf("private card in public search: %+v", got)
	}
	if got := cards(call("GET", "/api/cards/public?q=.*", bob, "", 200)); len(got) != 0 {
		t.Fatalf("search text treated as a pattern: %+v", got)
	}
	for _, c := range []Card{priv, pub} {
		call("PUT", "/api/cards/"+c.ID, bob, `{"name":"Bob was here"}`, 404)
		call("PUT", "/api/cards/"+c.ID+"/image", bob, photo, 404)
		call("DELETE", "/api/cards/"+c.ID, bob, "", 404)
	}
	if got := cards(call("GET", "/api/cards/public", ada, "", 200)); len(got) != 1 || !got[0].Mine {
		t.Fatalf("ada's public cards: %+v", got)
	}

	// Photos go to Cloudinary; a new photo replaces the old one there.
	img := "/api/cards/" + priv.ID + "/image"
	call("PUT", img, ada, "not a jpeg", 415)
	first := card(call("PUT", img, ada, photo, 200))
	second := card(call("PUT", img, ada, photo, 200))
	if first.PhotoURL == "" || second.PhotoURL == first.PhotoURL || !second.Mine || fake.count() != 1 {
		t.Fatalf("photo upload: %+v, %d photos stored", second, fake.count())
	}

	// Edits keep the photo; making the card public shows it to Bob, photo link included.
	edited := card(call("PUT", "/api/cards/"+priv.ID, ada, `{"name":"Grace B. Hopper","public":true}`, 200))
	if edited.Name != "Grace B. Hopper" || edited.Company != "" || !edited.Public || edited.PhotoURL != second.PhotoURL || !edited.CreatedAt.Equal(priv.CreatedAt) {
		t.Fatalf("update: %+v", edited)
	}
	if got := cards(call("GET", "/api/cards/public?q=HOPPER", bob, "", 200)); len(got) != 1 || got[0].PhotoURL != second.PhotoURL {
		t.Fatalf("card made public: %+v", got)
	}
	call("PUT", "/api/cards/nope", ada, `{"name":"x"}`, 404)

	// Deleting a card deletes its photo too.
	call("DELETE", "/api/cards/"+priv.ID, ada, "", 204)
	call("DELETE", "/api/cards/"+priv.ID, ada, "", 404)
	if fake.count() != 0 {
		t.Fatalf("%d photos left on Cloudinary", fake.count())
	}

	// Too many wrong passwords pause logins for that email, even with the right password.
	for range maxFailures {
		call("POST", "/api/auth/login", "", `{"email":"bob@example.com","password":"wrong password"}`, 401)
	}
	call("POST", "/api/auth/login", "", `{"email":"bob@example.com","password":"longenough"}`, 429)
}

// fakeCloudinary checks each request's signature the way Cloudinary does and keeps track of the stored photos.
type fakeCloudinary struct {
	*httptest.Server
	mu     sync.Mutex
	stored map[string]bool
}

func newFakeCloudinary(t *testing.T) *fakeCloudinary {
	f := &fakeCloudinary{stored: map[string]bool{}}
	f.Server = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		r.ParseForm()
		p := r.PostForm
		var signed []string
		for _, k := range slices.Sorted(maps.Keys(p)) {
			if k != "file" && k != "api_key" && k != "signature" {
				signed = append(signed, k+"="+p.Get(k))
			}
		}
		sum := sha1.Sum([]byte(strings.Join(signed, "&") + "secret"))
		if p.Get("signature") != hex.EncodeToString(sum[:]) || p.Get("api_key") != "key" || p.Get("type") != "authenticated" {
			http.Error(w, `{"error":{"message":"Invalid Signature"}}`, http.StatusUnauthorized)
			return
		}
		f.mu.Lock()
		defer f.mu.Unlock()
		id := p.Get("public_id")
		switch r.URL.Path {
		case "/v1_1/demo/image/upload":
			if !strings.HasPrefix(p.Get("file"), "data:image/jpeg;base64,") {
				http.Error(w, `{"error":{"message":"Invalid image file"}}`, http.StatusBadRequest)
				return
			}
			f.stored[id] = true
			json.NewEncoder(w).Encode(map[string]string{
				"public_id":  id,
				"secure_url": "https://res.cloudinary.com/demo/image/authenticated/s--sig--/v1/" + id + ".jpg",
			})
		case "/v1_1/demo/image/destroy":
			result := "not found"
			if f.stored[id] {
				result = "ok"
				delete(f.stored, id)
			}
			json.NewEncoder(w).Encode(map[string]string{"result": result})
		default:
			http.NotFound(w, r)
		}
	}))
	t.Cleanup(f.Close)
	return f
}

func (f *fakeCloudinary) count() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return len(f.stored)
}

// TestCloudinaryLive checks the real account: the upload's link opens the photo, the photo won't open without
// the link's signature, and deleting works.
func TestCloudinaryLive(t *testing.T) {
	raw := os.Getenv("CLOUDINARY_URL")
	if raw == "" {
		t.Skip("set CLOUDINARY_URL to check the real Cloudinary account")
	}
	c, err := newCloudinary(raw)
	if err != nil {
		t.Fatal(err)
	}
	var b bytes.Buffer
	if err := jpeg.Encode(&b, image.NewGray(image.Rect(0, 0, 8, 8)), nil); err != nil {
		t.Fatal(err)
	}
	ctx := context.Background()
	id, link, err := c.upload(ctx, b.Bytes())
	if err != nil {
		t.Fatal(err)
	}
	defer func() {
		if err := c.destroy(ctx, id); err != nil {
			t.Error(err)
		}
	}()
	status := func(u string) int {
		res, err := http.Get(u)
		if err != nil {
			t.Fatal(err)
		}
		res.Body.Close()
		return res.StatusCode
	}
	if got := status(link); got != http.StatusOK {
		t.Fatalf("signed link %s: %d", link, got)
	}
	unsigned := regexp.MustCompile(`/s--[^/]+--`).ReplaceAllString(link, "")
	if unsigned == link {
		t.Fatalf("link isn't signed: %s", link)
	}
	if got := status(unsigned); got == http.StatusOK {
		t.Fatalf("photo opens without the signature: %s", unsigned)
	}
}
