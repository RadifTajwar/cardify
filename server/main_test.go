package main

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/sha1"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"image"
	"image/jpeg"
	"io"
	"maps"
	"net/http"
	"net/http/httptest"
	"os"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"sync"
	"testing"
)

const photo = "\xFF\xD8\xFF\xE0 pretend jpeg"

// api is the server running on a throwaway database, so tests never touch real accounts or cards.
type api struct {
	t   *testing.T
	s   *store
	url string
}

func newAPI(t *testing.T) *api {
	uri := os.Getenv("CARDIFY_TEST_MONGODB_URI")
	if uri == "" {
		t.Skip("set CARDIFY_TEST_MONGODB_URI (e.g. mongodb://localhost:27017) to run this test")
	}
	ctx := context.Background()
	s, err := openStore(ctx, uri, "test_"+strings.ToLower(rand.Text()))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		s.db.Drop(ctx)
		s.db.Client().Disconnect(ctx)
	})
	srv := httptest.NewServer(s.handler())
	t.Cleanup(srv.Close)
	return &api{t, s, srv.URL}
}

func (a *api) call(method, path, token, body string, want int) []byte {
	a.t.Helper()
	req, _ := http.NewRequest(method, a.url+path, strings.NewReader(body))
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		a.t.Fatal(err)
	}
	defer res.Body.Close()
	b, _ := io.ReadAll(res.Body)
	if res.StatusCode != want {
		a.t.Fatalf("%s %s: got %d, want %d: %s", method, path, res.StatusCode, want, b)
	}
	return b
}

func decode[T any](t *testing.T, b []byte) (v T) {
	t.Helper()
	if err := json.Unmarshal(b, &v); err != nil {
		t.Fatalf("%v: %s", err, b)
	}
	return v
}

func token(t *testing.T, b []byte) string {
	t.Helper()
	return decode[struct{ Token string }](t, b).Token
}

func TestAPI(t *testing.T) {
	a := newAPI(t)
	call := a.call
	fake := newFakeCloudinary(t)
	a.s.photos = &cloudinary{fake.URL, "demo", "key", "secret"}

	// Accounts.
	call("POST", "/api/auth/signup", "", `{"name":"Ada","email":"not an email","password":"longenough"}`, 400)
	call("POST", "/api/auth/signup", "", `{"name":"Ada","email":"ada@example.com","password":"short"}`, 400)
	call("POST", "/api/auth/signup", "", `{"name":" ","email":"ada@example.com","password":"longenough"}`, 400)
	ada := token(t, call("POST", "/api/auth/signup", "", `{"name":"Ada","email":" Ada@Example.com ","password":"longenough"}`, 201))
	call("POST", "/api/auth/signup", "", `{"name":"Ada again","email":"ada@example.com","password":"longenough"}`, 409)
	call("POST", "/api/auth/login", "", `{"email":"ada@example.com","password":"wrong password"}`, 401)
	call("POST", "/api/auth/login", "", `{"email":"nobody@example.com","password":"longenough"}`, 401)
	ada2 := token(t, call("POST", "/api/auth/login", "", `{"email":"ADA@example.com","password":"longenough"}`, 200))
	call("GET", "/api/cards", ada2, "", 200)
	call("POST", "/api/auth/logout", ada2, "", 204)
	call("GET", "/api/cards", ada2, "", 401)
	call("GET", "/api/cards", "", "", 401)
	call("GET", "/api/cards", "made-up", "", 401)
	bob := token(t, call("POST", "/api/auth/signup", "", `{"name":"Bob","email":"bob@example.com","password":"longenough"}`, 201))

	// Cards start private, and the server owns id, owner and photo.
	call("POST", "/api/cards", ada, `{"title":"CEO"}`, 400)
	if got := strings.TrimSpace(string(call("GET", "/api/cards", ada, "", 200))); got != "[]" {
		t.Fatalf("empty list: %s", got) // the app parses a JSON array, so not null
	}
	priv := decode[Card](t, call("POST", "/api/cards", ada, `{"id":"evil","ownerId":"x","photoUrl":"x","name":"  Grace Hopper ","company":"Navy"}`, 201))
	if priv.ID == "evil" || priv.Name != "Grace Hopper" || priv.Public || !priv.Mine || priv.PhotoURL != "" || priv.OwnerName != "Ada" {
		t.Fatalf("create: %+v", priv)
	}
	pub := decode[Card](t, call("POST", "/api/cards", ada, `{"name":"Alan Turing","company":"Bletchley Park","public":true}`, 201))
	if got := decode[[]Card](t, call("GET", "/api/cards", ada, "", 200)); len(got) != 2 {
		t.Fatalf("ada's cards: %+v", got)
	}

	// Bob sees only Ada's public card, and can't change either of hers.
	if got := decode[[]Card](t, call("GET", "/api/cards", bob, "", 200)); len(got) != 0 {
		t.Fatalf("bob's cards: %+v", got)
	}
	if got := decode[[]Card](t, call("GET", "/api/cards/public", bob, "", 200)); len(got) != 1 || got[0].ID != pub.ID || got[0].Mine || got[0].OwnerName != "Ada" {
		t.Fatalf("public cards for bob: %+v", got)
	}
	if got := decode[[]Card](t, call("GET", "/api/cards/public?q=bletchley+P", bob, "", 200)); len(got) != 1 {
		t.Fatalf("search by company: %+v", got)
	}
	if got := decode[[]Card](t, call("GET", "/api/cards/public?q=hopper", bob, "", 200)); len(got) != 0 {
		t.Fatalf("private card in public search: %+v", got)
	}
	if got := decode[[]Card](t, call("GET", "/api/cards/public?q=.*", bob, "", 200)); len(got) != 0 {
		t.Fatalf("search text treated as a pattern: %+v", got)
	}
	for _, c := range []Card{priv, pub} {
		call("PUT", "/api/cards/"+c.ID, bob, `{"name":"Bob was here"}`, 404)
		call("PUT", "/api/cards/"+c.ID+"/image", bob, photo, 404)
		call("DELETE", "/api/cards/"+c.ID, bob, "", 404)
	}
	if got := decode[[]Card](t, call("GET", "/api/cards/public", ada, "", 200)); len(got) != 1 || !got[0].Mine {
		t.Fatalf("ada's public cards: %+v", got)
	}

	// Photos go to Cloudinary; a new photo replaces the old one there.
	img := "/api/cards/" + priv.ID + "/image"
	call("PUT", img, ada, "not a jpeg", 415)
	first := decode[Card](t, call("PUT", img, ada, photo, 200))
	second := decode[Card](t, call("PUT", img, ada, photo, 200))
	if first.PhotoURL == "" || second.PhotoURL == first.PhotoURL || !second.Mine || fake.count() != 1 {
		t.Fatalf("photo upload: %+v, %d photos stored", second, fake.count())
	}

	// Edits keep the photo; making the card public shows it to Bob, photo link included.
	edited := decode[Card](t, call("PUT", "/api/cards/"+priv.ID, ada, `{"name":"Grace B. Hopper","public":true}`, 200))
	if edited.Name != "Grace B. Hopper" || edited.Company != "" || !edited.Public || edited.PhotoURL != second.PhotoURL || !edited.CreatedAt.Equal(priv.CreatedAt) {
		t.Fatalf("update: %+v", edited)
	}
	if got := decode[[]Card](t, call("GET", "/api/cards/public?q=HOPPER", bob, "", 200)); len(got) != 1 || got[0].PhotoURL != second.PhotoURL {
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

func TestGoogleSignIn(t *testing.T) {
	a := newAPI(t)
	call := a.call
	call("POST", "/api/auth/google", "", `{"idToken":"ada"}`, 503) // not set up yet

	// A fake Google tokeninfo, where each token stands for one Google account.
	accounts := map[string]googleIdentity{
		"ada":        {Aud: "cardify-web", Iss: "https://accounts.google.com", Sub: "g-ada", Email: "Ada@Example.com", EmailVerified: "true", Name: "Ada L."},
		"grace":      {Aud: "cardify-web", Iss: "accounts.google.com", Sub: "g-grace", Email: "grace@example.com", EmailVerified: "true", Name: "Grace"},
		"other-app":  {Aud: "someone-else", Iss: "accounts.google.com", Sub: "g-x", Email: "x@example.com", EmailVerified: "true"},
		"unverified": {Aud: "cardify-web", Iss: "accounts.google.com", Sub: "g-u", Email: "u@example.com", EmailVerified: "false"},
	}
	google := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		g, ok := accounts[r.URL.Query().Get("id_token")]
		if !ok {
			http.Error(w, `{"error":"invalid_token"}`, http.StatusBadRequest)
			return
		}
		json.NewEncoder(w).Encode(g)
	}))
	defer google.Close()
	a.s.googleClientID, a.s.tokenInfoURL = "cardify-web", google.URL

	// Ada already has a password account; signing in with Google under the same email lands in it.
	adaPassword := token(t, call("POST", "/api/auth/signup", "", `{"name":"Ada","email":"ada@example.com","password":"longenough"}`, 201))
	call("POST", "/api/cards", adaPassword, `{"name":"Grace Hopper"}`, 201)
	adaGoogle := token(t, call("POST", "/api/auth/google", "", `{"idToken":"ada"}`, 200))
	if got := decode[[]Card](t, call("GET", "/api/cards", adaGoogle, "", 200)); len(got) != 1 {
		t.Fatalf("Google sign-in didn't reach Ada's account: %+v", got)
	}
	call("POST", "/api/auth/login", "", `{"email":"ada@example.com","password":"longenough"}`, 200) // her password still works

	// A new Google user gets an account, then the same one again, and has no password to log in with.
	call("POST", "/api/auth/google", "", `{"idToken":"grace"}`, 201)
	if u := decode[struct{ User User }](t, call("POST", "/api/auth/google", "", `{"idToken":"grace"}`, 200)).User; u.Name != "Grace" || u.Email != "grace@example.com" {
		t.Fatalf("grace: %+v", u)
	}
	call("POST", "/api/auth/login", "", `{"email":"grace@example.com","password":"anything at all"}`, 401)

	// Tokens Google rejects, tokens for another app, and unverified emails get nowhere.
	for _, tok := range []string{"forged", "other-app", "unverified"} {
		call("POST", "/api/auth/google", "", `{"idToken":"`+tok+`"}`, 401)
	}
}

func TestPasswordReset(t *testing.T) {
	a := newAPI(t)
	call := a.call
	call("POST", "/api/auth/forgot", "", `{"email":"ada@example.com"}`, 503) // not set up yet

	var mu sync.Mutex
	var sent []string // each email as "to|body"
	a.s.sendMail = func(_ context.Context, to, subject, body string) error {
		mu.Lock()
		defer mu.Unlock()
		sent = append(sent, to+"|"+body)
		return nil
	}
	lastCode := func() string {
		mu.Lock()
		defer mu.Unlock()
		return regexp.MustCompile(`\b\d{6}\b`).FindString(sent[len(sent)-1])
	}
	wrong := func(code string) string {
		n, _ := strconv.Atoi(code)
		return fmt.Sprintf("%06d", (n+1)%1_000_000)
	}
	reset := func(code, password string, want int) []byte {
		t.Helper()
		return call("POST", "/api/auth/reset", "", `{"email":"ada@example.com","code":"`+code+`","password":"`+password+`"}`, want)
	}

	old := token(t, call("POST", "/api/auth/signup", "", `{"name":"Ada","email":"ada@example.com","password":"old password"}`, 201))

	// Unknown emails get the same answer as known ones, and no email; a second ask within a minute sends nothing.
	call("POST", "/api/auth/forgot", "", `{"email":"nobody@example.com"}`, 204)
	call("POST", "/api/auth/forgot", "", `{"email":" ADA@example.com "}`, 204)
	call("POST", "/api/auth/forgot", "", `{"email":"ada@example.com"}`, 204)
	if len(sent) != 1 || !strings.HasPrefix(sent[0], "ada@example.com|") {
		t.Fatalf("emails sent: %q", sent)
	}
	code := lastCode()

	reset(wrong(code), "new password", 400)
	reset(code, "short", 400)
	fresh := token(t, reset(code, "new password", 200))
	call("GET", "/api/cards", fresh, "", 200)
	call("GET", "/api/cards", old, "", 401) // the reset logged the old session out
	reset(code, "another password", 400)    // a code works once
	call("POST", "/api/auth/login", "", `{"email":"ada@example.com","password":"old password"}`, 401)
	call("POST", "/api/auth/login", "", `{"email":"ada@example.com","password":"new password"}`, 200)

	// Guessing is capped: after maxMisses wrong codes, even the right one is refused.
	call("POST", "/api/auth/forgot", "", `{"email":"ada@example.com"}`, 204)
	code = lastCode()
	for range maxMisses {
		reset(wrong(code), "new password 2", 400)
	}
	reset(code, "new password 2", 429)
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

// TestGmailLive sends one real email with the GMAIL_* settings, to CARDIFY_TEST_MAIL_TO.
func TestGmailLive(t *testing.T) {
	to, g := os.Getenv("CARDIFY_TEST_MAIL_TO"), newGmail()
	if to == "" || g == nil {
		t.Skip("set GMAIL_* and CARDIFY_TEST_MAIL_TO to send a real test email")
	}
	if err := g.send(context.Background(), to, "Cardify test email", "If you can read this, Cardify can email password reset codes.\n"); err != nil {
		t.Fatal(err)
	}
}
