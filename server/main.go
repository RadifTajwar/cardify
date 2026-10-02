// Command server is the Cardify API: visiting cards in a JSON file, their photos as JPEGs beside it.
package main

import (
	"cmp"
	"crypto/rand"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"io"
	"io/fs"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"sync"
	"time"
)

type Card struct {
	ID        string    `json:"id"`
	Name      string    `json:"name"`
	Title     string    `json:"title"`
	Company   string    `json:"company"`
	Phone     string    `json:"phone"` // several numbers separated by newlines or commas
	Email     string    `json:"email"`
	Website   string    `json:"website"`
	Address   string    `json:"address"`
	Notes     string    `json:"notes"`
	HasImage  bool      `json:"hasImage"`
	CreatedAt time.Time `json:"createdAt"`
	UpdatedAt time.Time `json:"updatedAt"`
}

// ponytail: every card lives in memory and cards.json is rewritten on each change.
// Plenty for a wallet's worth of cards; move to SQLite if this ever holds tens of thousands.
type store struct {
	mu    sync.Mutex
	dir   string
	cards map[string]Card
}

func openStore(dir string) (*store, error) {
	if err := os.MkdirAll(filepath.Join(dir, "images"), 0o700); err != nil {
		return nil, err
	}
	s := &store{dir: dir, cards: map[string]Card{}}
	b, err := os.ReadFile(filepath.Join(dir, "cards.json"))
	if errors.Is(err, fs.ErrNotExist) {
		return s, nil
	}
	if err != nil {
		return nil, err
	}
	var list []Card
	if err := json.Unmarshal(b, &list); err != nil {
		return nil, err
	}
	for _, c := range list {
		s.cards[c.ID] = c
	}
	return s, nil
}

func (s *store) imagePath(id string) string { return filepath.Join(s.dir, "images", id+".jpg") }

// sorted returns the cards by name (company when there's no name). Caller holds mu.
func (s *store) sorted() []Card {
	list := make([]Card, 0, len(s.cards))
	for _, c := range s.cards {
		list = append(list, c)
	}
	key := func(c Card) string { return strings.ToLower(cmp.Or(c.Name, c.Company)) }
	slices.SortFunc(list, func(a, b Card) int { return cmp.Or(cmp.Compare(key(a), key(b)), cmp.Compare(a.ID, b.ID)) })
	return list
}

// set stores c under id (deletes id when c is nil) and persists, undoing the change if the write fails.
// Caller holds mu.
func (s *store) set(id string, c *Card) error {
	old, had := s.cards[id]
	if c != nil {
		s.cards[id] = *c
	} else {
		delete(s.cards, id)
	}
	b, err := json.MarshalIndent(s.sorted(), "", "  ")
	if err == nil {
		err = writeFileAtomic(filepath.Join(s.dir, "cards.json"), b)
	}
	if err != nil {
		if had {
			s.cards[id] = old
		} else {
			delete(s.cards, id)
		}
	}
	return err
}

// writeFileAtomic fsyncs a temp file and renames it over path, so a crash never leaves half a file.
func writeFileAtomic(path string, data []byte) error {
	tmp := path + ".tmp"
	f, err := os.OpenFile(tmp, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0o600)
	if err != nil {
		return err
	}
	_, err = f.Write(data)
	if err == nil {
		err = f.Sync()
	}
	if cerr := f.Close(); err == nil {
		err = cerr
	}
	if err != nil {
		return err
	}
	return os.Rename(tmp, path)
}

func (s *store) handler(token string) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /api/cards", s.list)
	mux.HandleFunc("POST /api/cards", s.create)
	mux.HandleFunc("PUT /api/cards/{id}", s.update)
	mux.HandleFunc("DELETE /api/cards/{id}", s.delete)
	mux.HandleFunc("GET /api/cards/{id}/image", s.getImage)
	mux.HandleFunc("PUT /api/cards/{id}/image", s.putImage)

	// ponytail: one shared token for a single-user app; add real accounts if others ever sign in.
	want := []byte("Bearer " + token)
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if subtle.ConstantTimeCompare([]byte(r.Header.Get("Authorization")), want) != 1 {
			http.Error(w, "invalid or missing token", http.StatusUnauthorized)
			return
		}
		mux.ServeHTTP(w, r)
	})
}

func (s *store) list(w http.ResponseWriter, r *http.Request) {
	s.mu.Lock()
	list := s.sorted()
	s.mu.Unlock()
	writeJSON(w, http.StatusOK, list)
}

func (s *store) create(w http.ResponseWriter, r *http.Request) {
	c, ok := decodeCard(w, r)
	if !ok {
		return
	}
	c.ID = rand.Text()
	c.CreatedAt = time.Now().UTC()
	c.UpdatedAt = c.CreatedAt
	s.mu.Lock()
	defer s.mu.Unlock()
	if err := s.set(c.ID, &c); err != nil {
		serverError(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, c)
}

func (s *store) update(w http.ResponseWriter, r *http.Request) {
	c, ok := decodeCard(w, r)
	if !ok {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	old, found := s.cards[r.PathValue("id")]
	if !found {
		http.NotFound(w, r)
		return
	}
	c.ID, c.HasImage, c.CreatedAt, c.UpdatedAt = old.ID, old.HasImage, old.CreatedAt, time.Now().UTC()
	if err := s.set(c.ID, &c); err != nil {
		serverError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, c)
}

func (s *store) delete(w http.ResponseWriter, r *http.Request) {
	s.mu.Lock()
	defer s.mu.Unlock()
	id := r.PathValue("id")
	if _, found := s.cards[id]; !found {
		http.NotFound(w, r)
		return
	}
	if err := s.set(id, nil); err != nil {
		serverError(w, err)
		return
	}
	if err := os.Remove(s.imagePath(id)); err != nil && !errors.Is(err, fs.ErrNotExist) {
		log.Printf("remove image of %s: %v", id, err)
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *store) getImage(w http.ResponseWriter, r *http.Request) {
	s.mu.Lock()
	c, found := s.cards[r.PathValue("id")]
	s.mu.Unlock()
	if !found || !c.HasImage {
		http.NotFound(w, r)
		return
	}
	http.ServeFile(w, r, s.imagePath(c.ID))
}

func (s *store) putImage(w http.ResponseWriter, r *http.Request) {
	b, err := io.ReadAll(http.MaxBytesReader(w, r.Body, 10<<20))
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	if http.DetectContentType(b) != "image/jpeg" {
		http.Error(w, "photo must be a JPEG", http.StatusUnsupportedMediaType)
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	c, found := s.cards[r.PathValue("id")]
	if !found {
		http.NotFound(w, r)
		return
	}
	if err := writeFileAtomic(s.imagePath(c.ID), b); err != nil {
		serverError(w, err)
		return
	}
	c.HasImage, c.UpdatedAt = true, time.Now().UTC()
	if err := s.set(c.ID, &c); err != nil {
		serverError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, c)
}

// decodeCard reads the editable fields from the body; id, image flag and timestamps stay server-owned.
func decodeCard(w http.ResponseWriter, r *http.Request) (Card, bool) {
	var in Card
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 64<<10)).Decode(&in); err != nil {
		http.Error(w, "invalid JSON: "+err.Error(), http.StatusBadRequest)
		return Card{}, false
	}
	t := strings.TrimSpace
	c := Card{
		Name: t(in.Name), Title: t(in.Title), Company: t(in.Company), Phone: t(in.Phone),
		Email: t(in.Email), Website: t(in.Website), Address: t(in.Address), Notes: t(in.Notes),
	}
	if c.Name == "" && c.Company == "" {
		http.Error(w, "a card needs a name or a company", http.StatusBadRequest)
		return Card{}, false
	}
	return c, true
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(v)
}

func serverError(w http.ResponseWriter, err error) {
	log.Print(err)
	http.Error(w, "server error, check the server log", http.StatusInternalServerError)
}

func main() {
	token := os.Getenv("CARDIFY_TOKEN")
	if token == "" {
		log.Fatal("set CARDIFY_TOKEN to the secret the app sends (cardify.token in android/gradle.properties)")
	}
	s, err := openStore(cmp.Or(os.Getenv("CARDIFY_DATA"), "data"))
	if err != nil {
		log.Fatal(err)
	}
	srv := &http.Server{
		Addr:              cmp.Or(os.Getenv("CARDIFY_ADDR"), ":8080"),
		Handler:           s.handler(token),
		ReadHeaderTimeout: 10 * time.Second,
	}
	log.Printf("cardify listening on %s, data in %s", srv.Addr, s.dir)
	log.Fatal(srv.ListenAndServe())
}
